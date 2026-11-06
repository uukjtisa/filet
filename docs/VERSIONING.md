# Versioning Policy

Filet follows **Semantic Versioning (SemVer)** to maintain a predictable and transparent release cycle. Version numbers communicate the nature and compatibility impact of changes introduced in each release.

## Effective Date

This versioning policy takes effect beginning with the **first release following version `0.2.0`**.

Version `0.2.0` and all preceding releases remain under the previous versioning approach. All subsequent releases will follow the principles and guidelines outlined in this document.

## Version Format

Filet uses the following version format:

`MAJOR.MINOR.PATCH`

For example: `1.4.2`

Each component has a specific meaning:

### MAJOR

Incremented when introducing backward-incompatible changes.

Examples:
- Breaking changes to public interfaces or APIs.
- Changes to configuration or data formats that require migration.
- Removal or replacement of existing functionality in a way that breaks compatibility.

Format: `1.4.2 → 2.0.0`

### MINOR

Incremented when introducing new functionality in a backward-compatible manner.

Examples:
- New features or capabilities.
- New integrations or supported protocols.
- Significant enhancements to existing functionality.
- New optional settings or configuration options.

Format: `1.4.2 → 1.5.0`

### PATCH

Incremented when introducing backward-compatible fixes and improvements.

Examples:
- Bug fixes and stability improvements.
- Performance and resource-usage optimizations.
- Minor UI/UX corrections.
- Internal refactoring that does not alter existing behavior or compatibility.

Format: `1.4.2 → 1.4.3`

## Version Increment Rules

When incrementing a version component, all components to its right are reset to zero.

| Change type | Version increment |
|---|---|
| Breaking change | MAJOR + 1 |
| New functionality | MINOR + 1, PATCH = 0 |
| Bug fix or compatible improvement | PATCH + 1, when applicable |

## Pre-1.0.0 Development

While Filet remains below `1.0.0`, its public interfaces and behavior may evolve more freely. Compatibility is not guaranteed between minor releases.

The `0.x.y` version range represents active development and does not imply that the application is incomplete or unusable.

The `1.0.0` release marks the beginning of a stable compatibility commitment for Filet's documented public interfaces.

## Additional Guidelines

- Version increments should reflect the actual impact of changes, not the amount of development effort involved.
- Multiple changes of the same release should be grouped into a single appropriate version increment.
- Internal implementation changes do not automatically warrant a MAJOR release.
- A release should never be assigned a higher version merely to make it appear more significant.
- Version numbers must be consistent across release tags, application metadata, and published release information.

## Guiding Principle

**A version number is a compatibility promise, not a measure of development progress.**

Filet's versioning should help users understand what has changed, what has improved, and whether an update may affect their existing experience.