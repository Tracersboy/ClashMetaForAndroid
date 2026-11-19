package config

import (
	"cfa/native/customnodes"

	"github.com/metacubex/mihomo/config"
)

func SetCustomNodes(content string) {
	customnodes.Set(content)
}

func patchCustomNodes(cfg *config.RawConfig, _ string) error {
	proxies, err := customnodes.Patch(cfg.Proxy, cfg.ProxyGroup, cfg.ProxyProvider)
	if err != nil {
		return err
	}

	cfg.Proxy = proxies

	return nil
}
