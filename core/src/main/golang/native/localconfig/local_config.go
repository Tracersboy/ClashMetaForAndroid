package localconfig

import (
	"encoding/json"
	"fmt"
	"os"
	"slices"
	"sort"
	"strings"

	"cfa/native/customnodes"

	"github.com/metacubex/mihomo/adapter"
	"github.com/metacubex/mihomo/adapter/provider"
	"github.com/metacubex/mihomo/common/convert"
	"github.com/metacubex/mihomo/common/yaml"
	"github.com/metacubex/mihomo/config"
	"github.com/metacubex/mihomo/rules"
	rulecommon "github.com/metacubex/mihomo/rules/common"
	"github.com/metacubex/mihomo/tunnel"
)

type Settings struct {
	Nodes []customnodes.Node `yaml:"nodes"`
	Rules []Rule             `yaml:"rules"`
}

type Rule struct {
	ID         string     `yaml:"id"`
	Definition Definition `yaml:"definition"`
	NodeID     string     `yaml:"nodeId"`
}

type Definition struct {
	Type    string   `json:"type" yaml:"type"`
	Payload string   `json:"payload,omitempty" yaml:"payload"`
	Target  string   `json:"target" yaml:"target"`
	Params  []string `json:"params,omitempty" yaml:"params"`
}

type RuleIssue struct {
	ID     string `json:"id"`
	Reason string `json:"reason"`
}

type Report struct {
	Renames    []customnodes.Rename `json:"renames,omitempty"`
	NodeNames  map[string]string    `json:"nodeNames,omitempty"`
	Targets    []string             `json:"targets,omitempty"`
	RuleIssues []RuleIssue          `json:"ruleIssues,omitempty"`
}

type Import struct {
	Nodes []map[string]any `json:"nodes,omitempty"`
	Rules []Definition     `json:"rules,omitempty"`
	Error string           `json:"error,omitempty"`
	Entry int              `json:"entry,omitempty"`
}

func Decode(content string) (Settings, error) {
	var settings Settings
	// JSON sent by Android is also YAML. Using the kernel's decoder here
	// preserves the same scalar types as parsing proxies from a profile.
	if err := yaml.Unmarshal([]byte(content), &settings); err != nil {
		return Settings{}, fmt.Errorf("invalid local configuration")
	}
	return settings, nil
}

func ParseInput(content string, ruleInput bool) Import {
	content = strings.TrimPrefix(content, "\ufeff")
	if strings.TrimSpace(content) == "" {
		return Import{Error: "empty"}
	}

	var lines []any
	for _, line := range strings.Split(content, "\n") {
		if line = strings.TrimSpace(line); line != "" && !strings.HasPrefix(line, "#") {
			lines = append(lines, line)
		}
	}
	if len(lines) == 0 {
		return Import{Error: "empty"}
	}
	first := lines[0].(string)
	var document any
	if ruleInput && !strings.HasPrefix(first, "-") && !strings.HasPrefix(first, "[") &&
		!strings.HasPrefix(first, "{") && !strings.HasPrefix(first, "rules:") {
		document = lines
	} else if err := yaml.Unmarshal([]byte(content), &document); err != nil {
		return Import{Error: "format"}
	}
	if mapping, ok := document.(map[string]any); ok {
		if ruleInput {
			document = mapping["rules"]
		} else if nodes, found := mapping["proxies"]; found {
			document = nodes
		} else {
			document = []any{mapping}
		}
	}
	items, ok := document.([]any)
	if !ok || len(items) == 0 {
		return Import{Error: "format"}
	}

	var imported Import
	for index, item := range items {
		if ruleInput {
			text, ok := item.(string)
			if !ok {
				return Import{Error: "rule", Entry: index + 1}
			}
			definition, ok := parseDefinition(text)
			if !ok {
				return Import{Error: "rule", Entry: index + 1}
			}
			imported.Rules = append(imported.Rules, definition)
		} else {
			mapping, ok := item.(map[string]any)
			if !ok || !validNode(mapping) {
				return Import{Error: "node", Entry: index + 1}
			}
			imported.Nodes = append(imported.Nodes, mapping)
		}
	}
	return imported
}

func validNode(mapping map[string]any) bool {
	name, _ := mapping["name"].(string)
	if name = strings.TrimSpace(name); name == "" || strings.ContainsAny(name, "\r\n\x00") {
		return false
	}
	mapping["name"] = name
	if _, err := json.Marshal(mapping); err != nil {
		return false
	}
	proxy, err := adapter.ParseProxy(mapping, adapter.WithTunnelForAPI(tunnel.Tunnel))
	if err != nil {
		return false
	}
	_ = proxy.Close()
	return true
}

func parseDefinition(text string) (Definition, bool) {
	text = strings.TrimSpace(text)
	if strings.ContainsAny(text, "\r\n\x00") {
		return Definition{}, false
	}
	ruleType, payload, target, params := rulecommon.ParseRulePayload(text, true)
	definition := Definition{Type: ruleType, Payload: payload, Target: target, Params: params}
	if target == "" {
		return Definition{}, false
	}
	_, err := rules.ParseRule(ruleType, payload, target, params, nil)
	return definition, err == nil
}

func (d Definition) line(target string) string {
	if d.Type == "MATCH" {
		return d.Type + "," + target
	}
	return strings.Join(append([]string{d.Type, d.Payload, target}, d.Params...), ",")
}

func Apply(cfg *config.RawConfig, settings Settings) (Report, error) {
	var report Report
	if slices.ContainsFunc(settings.Nodes, func(node customnodes.Node) bool { return node.Enabled }) {
		result, err := customnodes.Patch(cfg.Proxy, cfg.ProxyGroup, cfg.ProxyProvider, settings.Nodes, cachedProviderNodes(cfg)...)
		if err != nil {
			return report, err
		}
		cfg.Proxy = result.Proxies
		report.Renames = result.Renames
		report.NodeNames = result.NodeNames
	}

	targets := make(map[string]struct{})
	for name := range customnodes.ReservedNames {
		targets[name] = struct{}{}
	}
	for _, entries := range [][]map[string]any{cfg.Proxy, cfg.ProxyGroup} {
		for _, entry := range entries {
			if name, ok := entry["name"].(string); ok && name != "" {
				targets[name] = struct{}{}
			}
		}
	}
	for name := range targets {
		report.Targets = append(report.Targets, name)
	}
	sort.Strings(report.Targets)

	localRules := make([]string, 0, len(settings.Rules))
	for _, rule := range settings.Rules {
		definition := rule.Definition
		target := definition.Target
		if rule.NodeID != "" {
			target = report.NodeNames[rule.NodeID]
		}
		if reason := ruleIssue(cfg, definition, target, targets); reason != "" {
			report.RuleIssues = append(report.RuleIssues, RuleIssue{ID: rule.ID, Reason: reason})
			continue
		}
		localRules = append(localRules, definition.line(target))
	}
	if len(localRules) > 0 {
		cfg.Rule = append(localRules, cfg.Rule...)
	}
	return report, nil
}

func cachedProviderNodes(cfg *config.RawConfig) []string {
	var names []string
	for _, settings := range cfg.ProxyProvider {
		path, _ := settings["path"].(string)
		if path == "" {
			continue
		}
		info, err := os.Stat(path)
		if err != nil || !info.Mode().IsRegular() {
			continue
		}
		data, err := os.ReadFile(path)
		if err != nil {
			continue
		}
		var schema provider.ProxySchema
		if err := yaml.Unmarshal(data, &schema); err != nil {
			schema.Proxies, _ = convert.ConvertsV2Ray(data)
		}
		overrides, _ := settings["override"].(map[string]any)
		prefix, _ := overrides["additional-prefix"].(string)
		suffix, _ := overrides["additional-suffix"].(string)
		for _, item := range schema.Proxies {
			if name, ok := item["name"].(string); ok {
				names = append(names, prefix+name+suffix)
			}
		}
	}
	return names
}

func ruleIssue(cfg *config.RawConfig, definition Definition, target string, targets map[string]struct{}) string {
	if target == "" {
		return "target"
	}
	if definition.Type == "SUB-RULE" {
		if _, exists := cfg.SubRules[target]; !exists {
			return "sub_rule"
		}
	} else if _, exists := targets[target]; !exists {
		return "target"
	}
	// The kernel's syntax cannot encode separators inside a policy name.
	if strings.ContainsAny(target, ",\r\n\x00") {
		return "invalid"
	}
	parsed, err := rules.ParseRule(definition.Type, definition.Payload, target, definition.Params, nil)
	if err != nil {
		return "invalid"
	}
	for _, name := range parsed.ProviderNames() {
		if _, exists := cfg.RuleProvider[name]; !exists {
			return "rule_set"
		}
	}
	return ""
}
