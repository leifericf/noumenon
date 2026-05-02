# Noumenon

> **Experimental, early beta** — data model and interfaces are unstable. Expect breaking changes between releases.

**Precise, grounded answers about your codebase.**

Noumenon compiles repositories into a [Datomic](https://www.datomic.com) knowledge graph — commits, code segments, files, and architectural components — so AI agents answer codebase questions more accurately, faster, and cheaper than scanning raw source into context windows.

Full documentation, install instructions, configuration, deployment, and architecture: **[noumenon.leifericf.com](https://noumenon.leifericf.com)**.

## Quick start

```bash
curl -sSL https://noumenon.leifericf.com/install | bash
noum demo
noum ask noumenon "Describe the architecture"
```

Or run `noum` with no arguments for an interactive menu.

## License

MIT. See [`LICENSE`](LICENSE).
