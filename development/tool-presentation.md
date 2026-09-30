# Tool Presentation Contract

This contract defines the user-visible title and summary semantics for every built-in, MCP, and unknown tool. `ToolPresentationResolver` is the canonical lifecycle resolver and `MessageItemToolLabels` is the canonical summary renderer.

`ToolDetailPresentation` owns resource-backed detail content selection and result fields shared by
Compose and the WebUI. It consumes `ToolPresentationResolver` rather than resolving another lifecycle.
Compose retains layout, selection, prefix-aware JSON rendering, and local image loading; browser
projection stays demand-driven within watched message payloads. Failed/stopped content does not
replay completed MCP/search results, and shell exit codes remain command results rather than failures.

## Lifecycle

The visible lifecycle is deliberately small. `CALLING` and `RUNNING` share one active presentation. They must not create separate user-visible states or wording systems. Terminal presentations are completed, empty, failed, stopped, or running in background.

An exit code is a command result, not a tool failure. A completed shell call with any exit code uses `Command returned <code>`. Only transport, protocol, server, rejection, or other failures that prevent a usable command result use the failed presentation.

`wait_for_job` describes its own action after completion. Its card summary is `Waited for shell job <id>` when the ID is available. The exit code remains a compact detail status. Background summaries do not expose job IDs.

## Wording

Display names use title case and contain no lifecycle state. Active summaries use sentence case, present-progressive wording, and a Unicode ellipsis. Completed summaries use sentence case and past tense with no terminal period. Empty summaries explicitly state that no result exists and never masquerade as ordinary completion.

Failed summaries describe the attempted action before the subject, for example `Failed to read <path>`. A reliable server reason may be shown for shell, MCP, or unknown tools where no safe localized action summary exists. Stopped summaries use past tense. Background summaries state only that the job is running in the background and do not include its ID.

Reliable subjects and counts are shown. An unavailable count is not zero and must use a count-free default. Paths, commands, file names, IDs explicitly required by an action, and user input preserve their original case. Summary text describes lifecycle only. Result content and compact detail status must not replace it.

## Localization

Every locale contains the same summary keys and placeholder types. Languages may reorder indexed placeholders. Running text uses `…`. Terminal text has no final period. A change to lifecycle semantics must update every locale and the presentation contract tests in the same change.
