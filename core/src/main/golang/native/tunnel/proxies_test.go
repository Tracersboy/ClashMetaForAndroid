package tunnel

import (
	"testing"

	"github.com/metacubex/mihomo/adapter"
	C "github.com/metacubex/mihomo/constant"
)

func TestMarksOnlyTheLoadedCustomProxy(t *testing.T) {
	newProxy := func(name string) C.Proxy {
		t.Helper()
		proxy, err := adapter.ParseProxy(map[string]any{
			"name": name, "type": "http", "server": "192.0.2.1", "port": 8080,
		})
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = proxy.Close() })
		return proxy
	}

	base := newProxy("Example")
	custom := newProxy("Example冲突")
	provider := newProxy(custom.Name())
	proxies := []C.Proxy{base, custom, provider}
	SetCustomProxies(map[string]C.Proxy{base.Name(): base, custom.Name(): custom}, map[string]string{"custom": custom.Name()})
	t.Cleanup(func() { SetCustomProxies(nil, nil) })

	rows := convertProxies(proxies, nil)
	if rows[0].IsCustomNode || !rows[1].IsCustomNode || rows[2].IsCustomNode {
		t.Fatal("the badge must follow the loaded custom proxy, including its renamed alias")
	}

	SetCustomProxies(nil, nil)
	if convertProxies(proxies, nil)[1].IsCustomNode {
		t.Fatal("a different profile must clear stale custom proxy markers")
	}
}
