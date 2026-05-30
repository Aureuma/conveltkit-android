# Repo Rules

- Follow the shared workspace rules in `/home/shawn/Development/AGENTS.md`.

## ConveltKit Shared Version Train

- `convelt/Cargo.toml` `[workspace.package].version` is the canonical hard-coded version for `convelt`, `conveltkit-ios`, and `conveltkit-android`.
- Any tracked-content commit in one of these three repos must advance the next shared patch version across all three repos in the same release train.
- SDK repo version files are generated from Convelt and must not be treated as independent source-of-truth values.
- Public SDK identity remains `ConveltKit`; platform suffixes belong in repo/artifact names, not in user-facing SDK names.

## Android scope for this slice

- Keep this repository scaffold-only until the Android Billing implementation slice starts.
- Do not add BillingClient integration, publish workflows, or production billing logic in this slice.

## Node Package Manager
- For Node-based workspaces in this repository, the preferred package manager is `pnpm` (use `corepack pnpm ...` by default).

## Message Readability
- Emojify reports/messages where it improves readability, using relevant emojis only.

## Implementation Language
- Use Rust as much as possible, and write everything in Rust whenever practical. Avoid shell scripts unless absolutely necessary. For web-based work, or anything that requires a web interface, use SvelteKit/Svelte with TypeScript or JavaScript when it cannot be handled cleanly in Rust.
