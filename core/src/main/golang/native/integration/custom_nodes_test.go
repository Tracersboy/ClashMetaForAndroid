package integration

import (
	"os"
	"path/filepath"
	"reflect"
	"testing"

	"cfa/native/customnodes"
	"cfa/native/localconfig"
	mihomo "github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
	_ "github.com/metacubex/mihomo/hub/executor"
)

func parsedGroupProxies(t *testing.T, cfg *mihomo.Config, name string) []string {
	t.Helper()
	proxy, ok := cfg.Proxies[name]
	if !ok {
		t.Fatalf("proxy group %q not found", name)
	}
	group, ok := proxy.Adapter().(interface{ GetProxies(bool) []C.Proxy })
	if !ok {
		t.Fatalf("%q is not a proxy group", name)
	}

	var names []string
	for _, item := range group.GetProxies(false) {
		names = append(names, item.Name())
	}
	return names
}

func TestLocalConfigurationParsesWithMihomo(t *testing.T) {
	raw := mihomo.DefaultRawConfig()
	raw.Proxy = []map[string]any{{"name": "Original", "type": "http", "server": "192.0.2.2", "port": 8080}}
	raw.ProxyProvider = map[string]map[string]any{
		"Dynamic": {"type": "inline", "payload": []map[string]any{
			{"name": "ProviderNode", "type": "http", "server": "192.0.2.3", "port": 8080},
		}},
	}
	raw.ProxyGroup = []map[string]any{
		{"name": "Dynamic", "type": "select", "use": []string{"Dynamic"}},
		{"name": "All", "type": "select", "include-all": true},
	}
	raw.Rule = []string{"MATCH,All"}

	imported := localconfig.ParseInput(
		"  - {name: Original, type: anytls, server: 192.0.2.1, port: 443, password: demo, alpn: [h2, http/1.1]}\n"+
			"  - {name: Alternate, type: http, server: 192.0.2.4, port: 8080}\n",
		false,
	)
	if imported.Error != "" || len(imported.Nodes) != 2 {
		t.Fatalf("valid node import rejected: %s", imported.Error)
	}

	ruleInput := "# Preserves the order of a pasted list\n" +
		"  - 'DOMAIN,services.example.test,Original'\n" +
		"  - 'DOMAIN-SUFFIX,example.test,All'\n"
	rules := localconfig.ParseInput(ruleInput, true)
	if rules.Error != "" || len(rules.Rules) != 2 {
		t.Fatalf("valid rule import rejected: %s", rules.Error)
	}
	report, err := localconfig.Apply(raw, localconfig.Settings{
		Nodes: []customnodes.Node{
			{ID: "local", Enabled: true, Config: imported.Nodes[0]},
			{ID: "disabled", Enabled: false, Config: imported.Nodes[1]},
		},
		Rules: []localconfig.Rule{
			{ID: "domain", NodeID: "local", Definition: rules.Rules[0]},
			{ID: "suffix", Definition: rules.Rules[1]},
			{ID: "missing", Definition: localconfig.Definition{Type: "DOMAIN", Payload: "example.net", Target: "Unavailable"}},
			{ID: "provider", Definition: localconfig.Definition{Type: "RULE-SET", Payload: "missing-set", Target: "All"}},
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	if want := []localconfig.RuleIssue{{ID: "missing", Reason: "target"}, {ID: "provider", Reason: "rule_set"}}; !reflect.DeepEqual(report.RuleIssues, want) {
		t.Fatalf("rule issues: %+v", report.RuleIssues)
	}
	if want := []string{"DOMAIN,services.example.test,Original冲突", "DOMAIN-SUFFIX,example.test,All", "MATCH,All"}; !reflect.DeepEqual(raw.Rule, want) {
		t.Fatalf("effective rules: %v", raw.Rule)
	}

	parsed, err := mihomo.ParseRawConfig(raw)
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		for _, provider := range parsed.Providers {
			if closer, ok := provider.(interface{ Close() error }); ok {
				_ = closer.Close()
			}
		}
	}()
	if got, want := parsedGroupProxies(t, parsed, "Dynamic"), []string{"ProviderNode"}; !reflect.DeepEqual(got, want) {
		t.Fatalf("dynamic group: got %v, want %v", got, want)
	}
	if got, want := parsedGroupProxies(t, parsed, "All"), []string{"Original", "Original冲突", "ProviderNode"}; !reflect.DeepEqual(got, want) {
		t.Fatalf("all group: got %v, want %v", got, want)
	}
	if _, exists := parsed.Proxies["Alternate"]; exists {
		t.Fatal("disabled node present")
	}
	if len(parsed.Rules) != 3 || parsed.Rules[0].Adapter() != "Original冲突" {
		t.Fatal("local rule did not take first priority")
	}
}

func TestLocalConfigurationWireAndCachedProviderNames(t *testing.T) {
	settings, err := localconfig.Decode(`{
		"nodes": [{"id":"local", "enabled":true, "config":{
			"name":"Region Demo","type":"http","server":"192.0.2.6","port":8080,
			"headers":{"X-Example":"demo"},"tls":false
		}}],
		"rules": [{"id":"first", "definition":{"type":"IP-CIDR","payload":"192.0.2.0/24","target":"DIRECT","params":["no-resolve"]}}]
	}`)
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(t.TempDir(), "provider.yaml")
	if err := os.WriteFile(path, []byte("proxies:\n  - {name: Demo, type: http, server: 192.0.2.5, port: 8080}\n"), 0600); err != nil {
		t.Fatal(err)
	}
	raw := mihomo.DefaultRawConfig()
	raw.ProxyProvider = map[string]map[string]any{
		"Test": {"path": path, "override": map[string]any{"additional-prefix": "Region "}},
	}
	report, err := localconfig.Apply(raw, settings)
	if err != nil || report.NodeNames["local"] != "Region Demo冲突" {
		t.Fatalf("cached provider name conflict was not resolved: %v, %v", report.Renames, err)
	}
	if got := raw.Proxy[0]; got["port"] != 8080 || got["tls"] != false ||
		!reflect.DeepEqual(got["headers"], map[string]any{"X-Example": "demo"}) {
		t.Fatal("proxy scalar types or nested values changed across the bridge")
	}
	if raw.Rule[0] != "IP-CIDR,192.0.2.0/24,DIRECT,no-resolve" {
		t.Fatalf("rule option not preserved: %v", raw.Rule)
	}
}
