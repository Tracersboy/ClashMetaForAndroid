package customnodes

import (
	"fmt"
	"reflect"
	"strings"
	"testing"
)

const testCustomNodes = `[
  {"name":"HS-HK","type":"anytls","server":"192.0.2.1","port":20104,"password":"secret-a","client-fingerprint":"chrome","udp":true,"sni":"example.com","alpn":["h2","http/1.1"],"skip-cert-verify":true},
  {"name":"HS-SGP","type":"anytls","server":"192.0.2.2","port":20105,"password":"secret-b","client-fingerprint":"chrome","udp":true,"alpn":[],"skip-cert-verify":false}
]`

func useTestCustomNodes(t *testing.T, content string) {
	t.Helper()
	Set(content)
	t.Cleanup(func() { Set("[]") })
}

func TestPatchPrependsProxiesAndGroups(t *testing.T) {
	useTestCustomNodes(t, testCustomNodes)
	proxies := []map[string]any{{"name": "Subscription", "type": "ss"}}
	groups := []map[string]any{
		{"name": "Select", "type": "select", "proxies": []any{"HS-HK", "Subscription"}},
		{"name": "ProviderOnly", "type": "select", "use": []string{"provider"}},
		{"name": "StringSlice", "type": "fallback", "proxies": []string{"Subscription"}},
	}

	patched, err := Patch(proxies, groups, nil)
	if err != nil {
		t.Fatal(err)
	}

	if got := []string{patched[0]["name"].(string), patched[1]["name"].(string), patched[2]["name"].(string)}; !reflect.DeepEqual(got, []string{"HS-HK", "HS-SGP", "Subscription"}) {
		t.Fatalf("unexpected proxy order: %v", got)
	}
	if got := patched[0]["port"]; got != 20104 {
		t.Fatalf("port type/value was not preserved: %T %v", got, got)
	}
	if got := patched[0]["client-fingerprint"]; got != "chrome" {
		t.Fatalf("missing client fingerprint: %v", got)
	}

	for _, group := range groups {
		got, err := groupProxies(group["proxies"])
		if err != nil {
			t.Fatal(err)
		}
		want := []string{"HS-HK", "HS-SGP", "Subscription"}
		if group["name"] == "ProviderOnly" {
			want = []string{"HS-HK", "HS-SGP"}
		}
		if !reflect.DeepEqual(got, want) {
			t.Errorf("group %v: got %v, want %v", group["name"], got, want)
		}
		if group["name"] == "ProviderOnly" && !reflect.DeepEqual(group["use"], []string{"provider"}) {
			t.Errorf("provider references were changed: %v", group["use"])
		}
	}
}

func TestPatchEmptyListIsNoOp(t *testing.T) {
	useTestCustomNodes(t, "[]")
	proxies := []map[string]any{{"name": "Subscription"}}
	groups := []map[string]any{{"name": "Select", "proxies": []string{"Subscription"}}}

	patched, err := Patch(proxies, groups, nil)
	if err != nil {
		t.Fatal(err)
	}
	if len(patched) != 1 || !reflect.DeepEqual(groups[0]["proxies"], []string{"Subscription"}) {
		t.Fatal("empty custom node list changed the profile")
	}
}

func TestPatchRejectsNameConflict(t *testing.T) {
	useTestCustomNodes(t, testCustomNodes)
	proxies := []map[string]any{{"name": "HS-HK"}}

	_, err := Patch(proxies, nil, nil)
	if err == nil || !strings.Contains(err.Error(), "conflicts") {
		t.Fatalf("expected name conflict, got %v", err)
	}
}

func TestPatchRejectsReservedNames(t *testing.T) {
	for _, name := range []string{"DIRECT", "REJECT", "REJECT-DROP", "COMPATIBLE", "PASS", "PASS-RULE", "GLOBAL"} {
		t.Run(name, func(t *testing.T) {
			content := fmt.Sprintf(`[{"name":%q,"type":"anytls","server":"192.0.2.1","port":443,"password":"secret"}]`, name)
			useTestCustomNodes(t, content)
			if _, err := Patch(nil, nil, nil); err == nil || !strings.Contains(err.Error(), "reserved") {
				t.Fatalf("expected reserved name error, got %v", err)
			}
		})
	}
}

func TestPatchRejectsMalformedGroup(t *testing.T) {
	useTestCustomNodes(t, testCustomNodes)
	groups := []map[string]any{{"name": "Broken", "proxies": "Subscription"}}

	if _, err := Patch(nil, groups, nil); err == nil {
		t.Fatal("expected malformed proxies error")
	}
}

func TestPatchRejectsInvalidJSON(t *testing.T) {
	useTestCustomNodes(t, "not-json")
	if _, err := Patch(nil, nil, nil); err == nil {
		t.Fatal("expected JSON error")
	}
}

func TestPatchExpandsIncludeAllWithoutDuplicates(t *testing.T) {
	useTestCustomNodes(t, testCustomNodes)
	proxies := []map[string]any{{"name": "B"}, {"name": "A"}}
	groups := []map[string]any{
		{"name": "All", "proxies": []string{"B"}, "include-all": true},
		{"name": "Filtered", "include-all-proxies": true, "filter": "^A$"},
	}

	if _, err := Patch(proxies, groups, nil); err != nil {
		t.Fatal(err)
	}

	wantAll := []string{"HS-HK", "HS-SGP", "B", "A"}
	if got := groups[0]["proxies"]; !reflect.DeepEqual(got, wantAll) {
		t.Fatalf("include-all got %v, want %v", got, wantAll)
	}
	if groups[0]["include-all"] != false || groups[0]["include-all-proxies"] != false || groups[0]["include-all-providers"] != true {
		t.Fatalf("include-all flags were not normalized: %v", groups[0])
	}

	wantFiltered := []string{"HS-HK", "HS-SGP", "A"}
	if got := groups[1]["proxies"]; !reflect.DeepEqual(got, wantFiltered) {
		t.Fatalf("filtered include-all got %v, want %v", got, wantFiltered)
	}
}

func TestPatchProviderRenameAvoidsAllGroupNames(t *testing.T) {
	for _, reverse := range []bool{false, true} {
		t.Run(fmt.Sprintf("reverse-%v", reverse), func(t *testing.T) {
			useTestCustomNodes(t, testCustomNodes)
			providers := map[string]map[string]any{"P": {"type": "inline"}}
			groups := []map[string]any{
				{"name": "P", "use": []string{"P"}},
				{"name": "__cmfa_provider_P", "use": []string{"P"}},
			}
			if reverse {
				groups[0], groups[1] = groups[1], groups[0]
			}

			if _, err := Patch(nil, groups, providers); err != nil {
				t.Fatal(err)
			}
			if providers["__cmfa_provider_P_2"] == nil {
				t.Fatalf("provider was not assigned a collision-free name: %v", providers)
			}
			for _, group := range groups {
				if got := group["use"].([]string)[0]; got != "__cmfa_provider_P_2" {
					t.Fatalf("group %v has stale provider reference %q", group["name"], got)
				}
			}
		})
	}
}

func TestPatchRenamesProviderConflictsAndUpdatesUse(t *testing.T) {
	useTestCustomNodes(t, testCustomNodes)
	providers := map[string]map[string]any{"P": {"type": "http"}}
	groups := []map[string]any{
		{"name": "P", "use": []any{"P"}},
		{"name": "Other", "use": []string{"P"}},
	}

	if _, err := Patch(nil, groups, providers); err != nil {
		t.Fatal(err)
	}
	if _, exists := providers["P"]; exists {
		t.Fatal("conflicting provider name was not renamed")
	}
	newName := groups[0]["use"].([]string)[0]
	if !strings.HasPrefix(newName, "__cmfa_provider_P") {
		t.Fatalf("unexpected provider name %q", newName)
	}
	if providers[newName] == nil || groups[1]["use"].([]string)[0] != newName {
		t.Fatal("provider references were not updated")
	}
}
