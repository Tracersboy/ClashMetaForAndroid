package customnodes

import (
	"reflect"
	"testing"
)

func node(id, name string, enabled bool) Node {
	return Node{ID: id, Enabled: enabled, Config: map[string]any{
		"name": name, "type": "http", "server": "example.test", "port": 8080,
	}}
}

func TestPatchPreservesFirstAndDynamicGroups(t *testing.T) {
	proxies := []map[string]any{{"name": "Subscription"}, {"name": "Other"}}
	groups := []map[string]any{
		{"name": "Select", "proxies": []any{"Subscription", "Other"}, "default-selected": "Other"},
		{"name": "Dynamic", "use": []string{"provider"}},
		{"name": "All", "include-all": true},
		{"name": "Filter", "include-all-proxies": true, "filter": "^Subscription$"},
	}
	providers := map[string]map[string]any{"provider": {"type": "inline"}}
	result, err := Patch(proxies, groups, providers, []Node{
		node("a", "Local A", true), node("b", "Local B", false), node("c", "Local C", true),
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(result.Proxies) != 4 || result.Proxies[0]["name"] != "Subscription" ||
		result.Proxies[2]["name"] != "Local A" || result.NodeNames["b"] != "" {
		t.Fatalf("unexpected injected list or disabled node")
	}
	want := [][]string{
		{"Subscription", "Local A", "Local C", "Other"},
		nil,
		{"Other", "Local A", "Local C", "Subscription"},
		{"Subscription", "Local A", "Local C"},
	}
	for i, group := range groups {
		got, err := groupProxies(group["proxies"])
		if err != nil || !reflect.DeepEqual(got, want[i]) {
			t.Fatalf("group %s: got %v, want %v, err %v", group["name"], got, want[i], err)
		}
	}
	if groups[0]["default-selected"] != "Other" || !reflect.DeepEqual(groups[1]["use"], []string{"provider"}) {
		t.Fatal("existing default or dynamic members changed")
	}
	if groups[2]["include-all"] != false || groups[2]["include-all-providers"] != true {
		t.Fatal("include-all could put a custom node into the first position")
	}
}

func TestPatchRenamesConflictsWithoutChangingSubscriptionReferences(t *testing.T) {
	proxies := []map[string]any{{"name": "Original"}, {"name": "Original冲突"}}
	groups := []map[string]any{{"name": "Policy", "proxies": []string{"Original"}}}
	local := node("local", "Original", true)
	result, err := Patch(proxies, groups, nil, []Node{local, node("other", "Original冲突2", false)})
	if err != nil {
		t.Fatal(err)
	}
	if want := (Rename{ID: "local", From: "Original", To: "Original冲突3", Base: "Original"}); !reflect.DeepEqual(result.Renames, []Rename{want}) {
		t.Fatalf("renames: %+v", result.Renames)
	}
	if local.Config["name"] != "Original" || proxies[0]["name"] != "Original" {
		t.Fatal("changed an input name")
	}
	if got := groups[0]["proxies"]; !reflect.DeepEqual(got, []string{"Original", "Original冲突3"}) {
		t.Fatalf("proxy list: %v", got)
	}

	// A later profile may also occupy the name saved after the first collision.
	newLocal := node("local", "Original冲突3", true)
	newLocal.ConflictBase = "Original"
	result, err = Patch([]map[string]any{{"name": "Original冲突3"}}, nil, nil, []Node{newLocal})
	if err != nil || result.NodeNames["local"] != "Original冲突4" {
		t.Fatalf("did not reuse the original rename base: %v, %v", result.Renames, err)
	}
}

func TestPatchKeepsUseOnlyGroupAndRenamesCollidingStaticProvider(t *testing.T) {
	proxies := []map[string]any{{"name": "Subscription"}}
	providers := map[string]map[string]any{
		"Dynamic": {"type": "inline"},
		"Mixed":   {"type": "inline", "payload": []any{map[string]any{"name": "Local"}}},
	}
	groups := []map[string]any{
		{"name": "Dynamic", "use": []string{"Dynamic"}},
		{"name": "Mixed", "proxies": []string{"Subscription"}, "use": []string{"Mixed"}},
	}
	result, err := Patch(proxies, groups, providers, []Node{node("a", "Local", true)})
	if err != nil {
		t.Fatal(err)
	}
	if result.NodeNames["a"] != "Local冲突" || providers["Dynamic"] == nil || groups[0]["proxies"] != nil {
		t.Fatal("provider-only membership or provider node collision not preserved")
	}
	uses := groups[1]["use"].([]string)
	if len(uses) != 1 || uses[0] == "Mixed" || providers[uses[0]] == nil {
		t.Fatal("mixed group's provider reference was not kept functional")
	}
}

func TestPatchAvoidsDialerCycle(t *testing.T) {
	proxies := []map[string]any{{"name": "Subscription"}}
	groups := []map[string]any{
		{"name": "Outer", "proxies": []string{"Inner"}},
		{"name": "Inner", "proxies": []string{"Subscription"}},
		{"name": "Other", "proxies": []string{"Subscription"}},
	}
	local := node("a", "Local", true)
	local.Config["dialer-proxy"] = "Outer"
	_, err := Patch(proxies, groups, nil, []Node{local})
	if err != nil {
		t.Fatal(err)
	}
	for i, want := range [][]string{{"Inner"}, {"Subscription"}, {"Subscription", "Local"}} {
		if got := groups[i]["proxies"]; !reflect.DeepEqual(got, want) {
			t.Fatalf("group %s: got %v, want %v", groups[i]["name"], got, want)
		}
	}
}
