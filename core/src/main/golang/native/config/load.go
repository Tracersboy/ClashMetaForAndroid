package config

import (
	"errors"
	"os"
	P "path"
	"runtime"
	"strings"

	"cfa/native/app"
	"cfa/native/localconfig"
	"cfa/native/tunnel"

	"github.com/metacubex/mihomo/common/yaml"
	"github.com/metacubex/mihomo/config"
	"github.com/metacubex/mihomo/hub"
	"github.com/metacubex/mihomo/log"
)

func logDns(cfg *config.RawConfig) {
	bytes, err := yaml.Marshal(&cfg.DNS)
	if err != nil {
		log.Warnln("Marshal dns: %s", err.Error())

		return
	}

	log.Infoln("dns:")

	for _, line := range strings.Split(string(bytes), "\n") {
		log.Infoln("  %s", line)
	}
}

func readProfile(profilePath string) (*config.RawConfig, error) {
	configPath := P.Join(profilePath, "config.yaml")
	configData, err := os.ReadFile(configPath)
	if err != nil {
		return nil, err
	}
	raw, err := config.UnmarshalRawConfig(configData)
	if err != nil {
		return nil, err
	}
	return raw, prepareProfile(raw, profilePath)
}

func parse(raw *config.RawConfig, settings localconfig.Settings) (*config.Config, error) {
	cfg, err := config.ParseRawConfig(raw)
	if err != nil && (len(settings.Nodes) != 0 || len(settings.Rules) != 0) {
		// Parser errors can include values from imported proxy mappings.
		return nil, errors.New("subscription or local configuration failed validation")
	}
	return cfg, err
}

func Load(path string, settings localconfig.Settings, apply bool) (localconfig.Report, error) {
	raw, err := readProfile(path)
	if err != nil {
		return localconfig.Report{}, err
	}
	report, err := applyLocalConfig(raw, settings)
	if err != nil || !apply {
		return report, err
	}
	cfg, err := parse(raw, settings)
	if err != nil {
		return report, err
	}
	logDns(raw)
	hub.ApplyConfig(cfg)
	tunnel.SetCustomProxies(cfg.Proxies, report.NodeNames)
	app.ApplySubtitlePattern(raw.ClashForAndroid.UiSubtitlePattern)
	runtime.GC()
	return report, nil
}

func LoadDefault() {
	cfg, err := config.Parse([]byte{})
	if err != nil {
		panic(err.Error())
	}

	hub.ApplyConfig(cfg)
	tunnel.SetCustomProxies(nil, nil)
}
