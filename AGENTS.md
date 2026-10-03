# Project design rules

- Treat AdvancedSky as a new plugin with one current configuration, database schema and network protocol.
- Do not add or retain backward compatibility, upgrade migrations, legacy aliases, old-format readers or fallback implementations.
- Required settings and data must be present and valid. Report a clear error instead of guessing, silently filling missing settings, switching to another source or accepting an obsolete format.
- Generate bundled configuration on first installation. Load existing configuration as written; do not rewrite or upgrade it automatically.
- Normal game rules, explicitly optional features and operational retries remain part of the current design. They must not hide missing required data or substitute another implementation.
