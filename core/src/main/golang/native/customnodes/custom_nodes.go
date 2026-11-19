package customnodes

import (
	"fmt"
	"maps"
	"sort"
	"strconv"
	"strings"

	"github.com/dlclark/regexp2"
)

var ReservedNames = map[string]struct{}{
	"DIRECT": {}, "REJECT": {}, "REJECT-DROP": {}, "COMPATIBLE": {},
	"PASS": {}, "PASS-RULE": {}, "GLOBAL": {},
}

type Node struct {
	ID           string         `yaml:"id"`
	Config       map[string]any `yaml:"config"`
	Enabled      bool           `yaml:"enabled"`
	ConflictBase string         `yaml:"conflictBase"`
}

type Rename struct {
	ID   string `json:"id"`
	From string `json:"from"`
	To   string `json:"to"`
	Base string `json:"base"`
}

type Result struct {
	Proxies   []map[string]any
	NodeNames map[string]string
	Renames   []Rename
}

func unusedName(base, current string, occupied, localNames map[string]struct{}) string {
	start := uint64(1)
	if ending, found := strings.CutPrefix(current, base+"冲突"); found {
		start = 2
		if previous, err := strconv.ParseUint(ending, 10, 31); err == nil && previous >= start {
			start = previous + 1
		}
	}
	for suffix := start; ; suffix++ {
		name := base + "冲突"
		if suffix > 1 {
			name += strconv.FormatUint(suffix, 10)
		}
		_, used := occupied[name]
		_, local := localNames[name]
		if !used && !local {
			return name
		}
	}
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
		members, _ := groupProxies(group["proxies"])
		if _, conflict := providers[name]; name == "" || !conflict || len(members) == 0 {
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

func expandIncludeAllProxies(group map[string]any, allProxies []string, providerCount int) error {
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

	if len(existing) == 0 {
		use, err := stringList(group["use"], "use")
		if err != nil {
			return err
		}
		includesProviders, _ := group["include-all-providers"].(bool)
		if (!includesProviders && len(use) == 0) || (includesProviders && providerCount == 0) {
			fallback, _ := group["empty-fallback"].(string)
			if fallback == "" {
				fallback = "COMPATIBLE"
			}
			existing = []string{fallback}
		}
	}
	group["proxies"] = existing

	return nil
}

// Patch operates on the just-decoded profile. The original profile file is never rewritten.
func Patch(proxies []map[string]any, groups []map[string]any, providers map[string]map[string]any, nodes []Node, providerNodeNames ...string) (Result, error) {
	result := Result{Proxies: proxies}
	occupied := maps.Clone(ReservedNames)
	allProxies := make([]string, 0, len(proxies))
	for _, proxy := range proxies {
		if name, ok := proxy["name"].(string); ok && name != "" {
			occupied[name] = struct{}{}
			allProxies = append(allProxies, name)
		}
	}
	for _, group := range groups {
		if name, ok := group["name"].(string); ok {
			occupied[name] = struct{}{}
		}
	}
	for _, provider := range providers {
		reserveProviderNames(occupied, provider["payload"])
	}
	for _, name := range providerNodeNames {
		occupied[name] = struct{}{}
	}
	sort.Strings(allProxies)

	localNames := make(map[string]struct{}, len(nodes))
	for _, node := range nodes {
		if name, ok := node.Config["name"].(string); ok {
			localNames[name] = struct{}{}
		}
	}

	result.NodeNames = make(map[string]string)
	added := make([]map[string]any, 0, len(nodes))
	for _, node := range nodes {
		if !node.Enabled {
			continue
		}
		mapping := maps.Clone(node.Config)
		name, _ := mapping["name"].(string)
		if strings.TrimSpace(name) == "" || node.ID == "" {
			return Result{}, fmt.Errorf("invalid saved custom node")
		}
		if _, exists := occupied[name]; exists {
			base := node.ConflictBase
			if base == "" {
				base = name
			}
			newName := unusedName(base, name, occupied, localNames)
			result.Renames = append(result.Renames, Rename{ID: node.ID, From: name, To: newName, Base: base})
			name = newName
			mapping["name"] = name
		}
		occupied[name] = struct{}{}
		result.NodeNames[node.ID] = name
		added = append(added, mapping)
	}
	if len(added) == 0 {
		return result, nil
	}

	// First expand only the original static members. This also prevents include-all
	// from unexpectedly sorting a custom node into the first position.
	dependencies := make(map[string][]string)
	for _, proxy := range append(append([]map[string]any{}, proxies...), added...) {
		if name, _ := proxy["name"].(string); name != "" {
			if dialer, _ := proxy["dialer-proxy"].(string); dialer != "" {
				dependencies[name] = []string{dialer}
			}
		}
	}
	for _, group := range groups {
		if err := expandIncludeAllProxies(group, allProxies, len(providers)); err != nil {
			return Result{}, err
		}
		members, err := groupProxies(group["proxies"])
		if err != nil {
			return Result{}, err
		}
		if name, ok := group["name"].(string); ok {
			dependencies[name] = members
		}
	}

	for _, group := range groups {
		name, _ := group["name"].(string)
		members := dependencies[name]
		if len(members) == 0 {
			continue // use-only groups have a dynamic first member.
		}
		seen := make(map[string]struct{}, len(members))
		for _, member := range members {
			seen[member] = struct{}{}
		}
		inserted := make([]string, 0, len(added))
		for _, proxy := range added {
			member := proxy["name"].(string)
			if _, exists := seen[member]; exists || dependsOn(member, name, dependencies, make(map[string]bool)) {
				continue
			}
			inserted = append(inserted, member)
		}
		patched := make([]string, 0, len(members)+len(inserted))
		patched = append(patched, members[0])
		patched = append(patched, inserted...)
		patched = append(patched, members[1:]...)
		group["proxies"] = patched
		dependencies[name] = patched
	}
	if err := renameConflictingProviders(groups, providers); err != nil {
		return Result{}, err
	}
	result.Proxies = append(append([]map[string]any{}, proxies...), added...)
	return result, nil
}

func reserveProviderNames(occupied map[string]struct{}, payload any) {
	switch entries := payload.(type) {
	case []map[string]any:
		for _, entry := range entries {
			if name, ok := entry["name"].(string); ok {
				occupied[name] = struct{}{}
			}
		}
	case []any:
		for _, entry := range entries {
			if mapping, ok := entry.(map[string]any); ok {
				if name, ok := mapping["name"].(string); ok {
					occupied[name] = struct{}{}
				}
			}
		}
	}
}

func dependsOn(from, target string, dependencies map[string][]string, visited map[string]bool) bool {
	if from == target {
		return true
	}
	if visited[from] {
		return false
	}
	visited[from] = true
	for _, edge := range dependencies[from] {
		if dependsOn(edge, target, dependencies, visited) {
			return true
		}
	}
	return false
}
