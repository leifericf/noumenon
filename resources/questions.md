# Noumenon Question Catalog

Questions that can be asked of the AI query agent, ranked by what the model can answer. Use these as examples for benchmarking and testing.

## Tier 1: Fully answerable

These questions can be answered by the AI agent constructing Datalog queries against the current schema.

### Temporal & Churn

1. Which files have had the most bug fixes in the past 3 months?
2. Are there periods when we had an influx of bugs, and what was going on at that time?
3. How has the ratio of feature vs fix work changed over time per directory?
4. What is the code churn rate, and where is it highest?
5. Files ranked by total churn magnitude (lines added + deleted)
6. Did our refactoring efforts pay off in reduced churn?
7. Which commits introduced the most scattered changes across the directory tree?

### Ownership & Expertise

8. Who is the de facto expert for this subsystem?
9. What is the bus/truck factor for each module?
10. If developer X leaves, which parts become orphaned?
11. Which areas have knowledge concentrated in a single person?
12. Which developers have fractal (many small touches) vs deep (focused) contribution patterns?
13. Files with most distinct authors — does that correlate with defect rate?

### Defect Analysis

14. Which user produces the greatest number of bugs?
15. Who fixed the bugs created by Bob?
16. Which files are defect attractors?
17. Which code smells or patterns correlate with higher defect rates?
18. What percentage of development effort goes to low-quality code?

### Quality & Risk

19. Where do we have the most technical debt, and is it centralized?
20. Which files always change together despite no compile-time dependency?
21. What is the blast radius of a change to module X?
22. Which functions have safety concerns, and how recently were they modified?
23. Which deprecated functions are still being modified?
24. What is the fragility index of a module?
25. Is a file safe to refactor?
26. Rank modules by maintainability risk (bus factor + code health + coupling + defect history)

### Code Intelligence (LLM analyzer attributes)

27. Which functions are never called by any other segment? (dead code detection)
28. Which files have the most external dependencies?
29. Which dependencies are shared across the most files?
30. Which segments are pure and safe to refactor?
31. Which files depend on a specific module/namespace?
32. What percentage of our code appears AI-generated, and how does its quality compare?

### Import Graph (requires `enrich`)

33. Which tests should I run after changing this file?
34. Which architectural boundaries are being crossed by imports?
35. Dependency usage drift across codebase?
36. What is the full transitive impact of changing module X?
37. Which files are import hotspots (most imported)?
38. Are there any circular import dependencies?
39. Which files are orphaned (neither import nor are imported)?
40. Which imports cross top-level directory boundaries?
41. Which files change together despite having no import relationship? (hidden coupling)

## Tier 2: Partially answerable (future features needed)

42. Which functions have the highest churn relative to complexity? *(needs segment-level commit attribution)*
43. Modules growing in complexity monotonically? *(needs repeated analysis over time)*
44. Recently stable files now experiencing change spikes? *(approximable with windowed queries)*
45. Cross-boundary temporal coupling beyond imports? *(needs component data populated)*
46. Is tech debt growing or shrinking per module? *(needs historical snapshots)*
47. Hotspots getting worse despite development? *(needs historical quality comparison)*
48. Code written under time pressure, never refactored? *(needs line-level tracking)*
49. Git history contradicts documentation? *(heuristic, partially queryable)*
50. Hastily written code segments? *(partially queryable)*

## Tier 3: Out of scope

51. Production incidents traced to high-churn code? *(no incident tracking)*
52. Code half-life per module? *(needs line-level change tracking)*
53. Copy-pasted code divergence tracking? *(no clone detection)*

## Backlog items to unlock Tier 2

| Item | Unlocks | Description |
|------|---------|-------------|
| Segment-level commit attribution | Q42 | Match commit diffs with code/line-start/line-end |
| Repeated analysis over time | Q43, Q46, Q47 | Re-run LLM analysis, use Datomic history |
| Windowed commit aggregation | Q44 | Parameterized queries with time windows |
| Component entity population | Q45 | Populate component/name, files, depends-on |
| ~~Deterministic import extraction~~ | ~~Q33-Q41~~ | ~~Done — `enrich` command~~ |
| Line-level change tracking | Q48, Q52 | Per-file-per-commit diff stats join entity |
| Docstring staleness detection | Q49 | Compare docstring tx-time with commit frequency |
| Commit heuristic enrichment | Q50 | Add hour-of-day, day-of-week to commits |
| Clone detection | Q53 | Near-duplicate segment comparison |

## Sources

- User questions from planning discussions
- CodeScene behavioral code analysis (Adam Tornhill, "Your Code as a Crime Scene")
- Sillito, Murphy, De Volder (FSE 2006) — 44 question types during evolution tasks
- Nagappan & Ball (ICSE 2005) — relative code churn predicts defect density
- GitClear, LinearB, Sourcegraph product research
- Stack Overflow developer surveys on knowledge silos
