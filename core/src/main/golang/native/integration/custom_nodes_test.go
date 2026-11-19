package integration

import (
	"reflect"
	"testing"

	"cfa/native/customnodes"
	mihomo "github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
	_ "github.com/metacubex/mihomo/hub/executor"
)

const integrationCustomNodes = `[{"name":"HS-HK","type":"anytls","server":"192.0.2.1","port":20104,"password":"secret","client-fingerprint":"chrome","udp":true,"sni":"example.com","alpn":["h2","http/1.1"],"skip-cert-verify":true}]`

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

	proxies := group.GetProxies(false)
	names := make([]string, 0, len(proxies))
	for _, item := range proxies {
		names = append(names, item.Name())
	}

	return names
}

func TestCustomNodesParseWithProviderConflictAndIncludeAll(t *testing.T) {
	customnodes.Set(integrationCustomNodes)
	t.Cleanup(func() { customnodes.Set("[]") })

	raw := mihomo.DefaultRawConfig()
	raw.Proxy = []map[string]any{
		{"name": "Subscription", "type": "ss", "server": "192.0.2.2", "port": 8388, "cipher": "aes-128-gcm", "password": "secret"},
	}
	raw.ProxyProvider = map[string]map[string]any{
		"P": {
			"type": "inline",
			"payload": []map[string]any{
				{"name": "ProviderNode", "type": "ss", "server": "192.0.2.3", "port": 8388, "cipher": "aes-128-gcm", "password": "secret"},
			},
		},
	}
	raw.ProxyGroup = []map[string]any{
		{"name": "P", "type": "select", "use": []string{"P"}},
		{"name": "All", "type": "select", "include-all": true},
	}

	patched, err := customnodes.Patch(raw.Proxy, raw.ProxyGroup, raw.ProxyProvider)
	if err != nil {
		t.Fatal(err)
	}
	raw.Proxy = patched

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

	if got, want := parsedGroupProxies(t, parsed, "P"), []string{"HS-HK", "ProviderNode"}; !reflect.DeepEqual(got, want) {
		t.Fatalf("provider conflict group got %v, want %v", got, want)
	}
	if got, want := parsedGroupProxies(t, parsed, "All"), []string{"HS-HK", "Subscription", "ProviderNode"}; !reflect.DeepEqual(got, want) {
		t.Fatalf("include-all group got %v, want %v", got, want)
	}
}
