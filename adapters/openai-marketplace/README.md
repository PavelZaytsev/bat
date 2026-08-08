# Local BAT marketplace adapter for ChatGPT and Codex

Create a marketplace root with this layout:

```text
team-bat-marketplace/
├── .agents/plugins/marketplace.json
└── plugins/bat/
```

Copy `marketplace.json` from this directory to the path shown above. Put the complete BAT plugin
folder at `plugins/bat/`; that folder must contain `.codex-plugin/plugin.json`, `skills/`,
`scripts/`, and `bin/`. The marketplace path is resolved from `team-bat-marketplace/`, not from the
directory containing `marketplace.json`.

Restart the ChatGPT desktop app, select **Plugins**, choose **BAT Team**, and install **BAT**. For
Codex CLI discovery, add `team-bat-marketplace/` as the marketplace root and then install
`bat@bat-team`.
