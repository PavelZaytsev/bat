# Local ChatGPT/Codex marketplace adapter

Create a marketplace root with this layout:

```text
team-bdr-marketplace/
├── .agents/plugins/marketplace.json
└── plugins/bdr/
```

Copy `marketplace.json` from this directory to the path shown above. Put the complete BDR plugin
folder at `plugins/bdr/`; that folder must contain `.codex-plugin/plugin.json`, `skills/`,
`scripts/`, and `bin/`. The marketplace path is resolved from `team-bdr-marketplace/`, not from the
directory containing `marketplace.json`.

Restart the ChatGPT desktop app, select **Plugins**, choose **BDR Team**, and install **BDR**. For
Codex CLI discovery, add `team-bdr-marketplace/` as the marketplace root and then install
`bdr@bdr-team`.
