package customnodes

import (
	"encoding/json"
	"fmt"
	"sort"
	"strings"
	"sync"

	"github.com/dlclark/regexp2"
)

var state = struct {
	sync.RWMutex
	content string
}{content: "[]"}

var reservedNames = map[string]struct{}{
	"DIRECT": {}, "REJECT": {}, "REJECT-DROP": {}, "COMPATIBLE": {},
	"PASS": {}, "PASS-RULE": {}, "GLOBAL": {},
}

func Set(content string) {
	state.Lock()
	defer state.Unlock()

	if strings.TrimSpace(content) == "" {
		content = "[]"
	}
	state.content = content
}

type customNode struct {
	Name              string   `json:"name"`
	Type              string   `json:"type"`
	Server            string   `json:"server"`
	Port              int      `json:"port"`
	Password          string   `json:"password"`
	ClientFingerprint string   `json:"client-fingerprint"`
	UDP               bool     `json:"udp"`
	SNI               string   `json:"sni"`
	ALPN              []string `json:"alpn"`
	SkipCertVerify    bool     `json:"skip-cert-verify"`
}

func (n *customNode) validate() error {
	switch {
	case n.Name == "":
		return fmt.Errorf("custom node name must not be empty")
	case n.Type != "anytls":
		return fmt.Errorf("custom node %q: unsupported type %q", n.Name, n.Type)
	case n.Server == "":
		return fmt.Errorf("custom node %q: server must not be empty", n.Name)
	case n.Port < 1 || n.Port > 65535:
		return fmt.Errorf("custom node %q: invalid port %d", n.Name, n.Port)
	case n.Password == "":
		return fmt.Errorf("custom node %q: password must not be empty", n.Name)
	}
	if _, reserved := reservedNames[n.Name]; reserved {
		return fmt.Errorf("custom node name %q is reserved by the core", n.Name)
	}

	return nil
}

func (n *customNode) mapping() map[string]any {
	mapping := map[string]any{
		"name":             n.Name,
		"type":             n.Type,
		"server":           n.Server,
		"port":             n.Port,
		"password":         n.Password,
		"udp":              n.UDP,
		"skip-cert-verify": n.SkipCertVerify,
	}

	if n.ClientFingerprint != "" {
		mapping["client-fingerprint"] = n.ClientFingerprint
	}
	if n.SNI != "" {
		mapping["sni"] = n.SNI
	}
	if len(n.ALPN) > 0 {
		mapping["alpn"] = append([]string(nil), n.ALPN...)
	}

	return mapping
}

func read() ([]customNode, error) {
	state.RLock()
	content := state.content
	state.RUnlock()

	var nodes []customNode
	if err := json.Unmarshal([]byte(content), &nodes); err != nil {
		return nil, fmt.Errorf("decode custom nodes: %w", err)
	}

	return nodes, nil
}

func stringList(value any, field string) ([]string, error) {
	switch items := value.(type) {
	case nil:
		return nil, nil
	case []string:
		return append([]string(nil), items...), nil
	case []any:
		result := make([]string, 0, len(items))
		for _, item := range items {
			name, ok := item.(string)
			if !ok {
				return nil, fmt.Errorf("%s contains a non-string reference", field)
			}
			result = append(result, name)
		}
		return result, nil
	default:
		return nil, fmt.Errorf("field %q must be a string list", field)
	}
}

func groupProxies(value any) ([]string, error) {
	return stringList(value, "proxies")
}

func renameConflictingProviders(groups []map[string]any, providers map[string]map[string]any) error {
	if len(providers) == 0 {
		return nil
	}

	reserved := make(map[string]struct{}, len(providers)+len(groups))
	for name := range providers {
		reserved[name] = struct{}{}
	}
	for _, group := range groups {
		if name, ok := group["name"].(string); ok {
			reserved[name] = struct{}{}
		}
	}

	renames := make(map[string]string)
	for _, group := range groups {
		name, _ := group["name"].(string)
		if _, conflict := providers[name]; name == "" || !conflict {
			continue
		}

		base := "__cmfa_provider_" + name
		newName := base
		for suffix := 2; ; suffix++ {
			if _, exists := reserved[newName]; !exists {
				break
			}
			newName = fmt.Sprintf("%s_%d", base, suffix)
		}

		providers[newName] = providers[name]
		delete(providers, name)
		reserved[newName] = struct{}{}
		renames[name] = newName
	}

	for i, group := range groups {
		raw, exists := group["use"]
		if !exists {
			continue
		}
		uses, err := stringList(raw, "use")
		if err != nil {
			name, _ := group["name"].(string)
			return fmt.Errorf("proxy group %q at index %d: %w", name, i, err)
		}
		for j, name := range uses {
			if renamed, ok := renames[name]; ok {
				uses[j] = renamed
			}
		}
		group["use"] = uses
	}

	return nil
}

func expandIncludeAllProxies(group map[string]any, allProxies []string) error {
	includeAll, _ := group["include-all"].(bool)
	includeAllProxies, _ := group["include-all-proxies"].(bool)
	if !includeAll && !includeAllProxies {
		return nil
	}

	existing, err := groupProxies(group["proxies"])
	if err != nil {
		return err
	}

	if includeAll {
		group["include-all"] = false
		group["include-all-providers"] = true
	}
	group["include-all-proxies"] = false

	seen := make(map[string]struct{}, len(existing)+len(allProxies))
	for _, name := range existing {
		seen[name] = struct{}{}
	}

	filter, _ := group["filter"].(string)
	var filters []*regexp2.Regexp
	if filter != "" {
		for _, expression := range strings.Split(filter, "`") {
			compiled, err := regexp2.Compile(expression, regexp2.None)
			if err != nil {
				return fmt.Errorf("invalid proxy group filter: %w", err)
			}
			filters = append(filters, compiled)
		}
	}

	for _, name := range allProxies {
		if _, exists := seen[name]; exists {
			continue
		}

		matched := len(filters) == 0
		for _, expression := range filters {
			if match, _ := expression.MatchString(name); match {
				matched = true
				break
			}
		}
		if matched {
			existing = append(existing, name)
			seen[name] = struct{}{}
		}
	}

	group["proxies"] = existing

	return nil
}

func Patch(proxies []map[string]any, groups []map[string]any, providers map[string]map[string]any) ([]map[string]any, error) {
	nodes, err := read()
	if err != nil || len(nodes) == 0 {
		return proxies, err
	}

	occupied := make(map[string]struct{}, len(proxies)+len(groups)+len(nodes))
	allProxies := make([]string, 0, len(proxies))
	for _, proxy := range proxies {
		if name, ok := proxy["name"].(string); ok && name != "" {
			occupied[name] = struct{}{}
			allProxies = append(allProxies, name)
		}
	}
	for _, group := range groups {
		if name, ok := group["name"].(string); ok && name != "" {
			occupied[name] = struct{}{}
		}
	}
	sort.Strings(allProxies)

	mappings := make([]map[string]any, 0, len(nodes))
	names := make([]string, 0, len(nodes))
	customNames := make(map[string]struct{}, len(nodes))
	for i := range nodes {
		node := &nodes[i]
		if err := node.validate(); err != nil {
			return nil, err
		}
		if _, exists := customNames[node.Name]; exists {
			return nil, fmt.Errorf("duplicate custom node name %q", node.Name)
		}
		if _, exists := occupied[node.Name]; exists {
			return nil, fmt.Errorf("custom node %q conflicts with a profile proxy or group", node.Name)
		}

		occupied[node.Name] = struct{}{}
		customNames[node.Name] = struct{}{}
		mappings = append(mappings, node.mapping())
		names = append(names, node.Name)
	}

	if err := renameConflictingProviders(groups, providers); err != nil {
		return nil, err
	}

	result := append(mappings, proxies...)

	for i, group := range groups {
		if err := expandIncludeAllProxies(group, allProxies); err != nil {
			name, _ := group["name"].(string)
			return nil, fmt.Errorf("proxy group %q at index %d: %w", name, i, err)
		}

		existing, err := groupProxies(group["proxies"])
		if err != nil {
			name, _ := group["name"].(string)
			return nil, fmt.Errorf("proxy group %q at index %d: %w", name, i, err)
		}

		filtered := make([]string, 0, len(existing))
		for _, name := range existing {
			if _, custom := customNames[name]; !custom {
				filtered = append(filtered, name)
			}
		}

		prefixed := make([]string, 0, len(names)+len(filtered))
		prefixed = append(prefixed, names...)
		prefixed = append(prefixed, filtered...)
		group["proxies"] = prefixed
	}

	return result, nil
}
