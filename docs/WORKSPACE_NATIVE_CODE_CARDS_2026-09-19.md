# Workspace native code cards — September 19, 2026

Implemented only on `agent/myra-phase-1`, after phone recordings showed raw fenced HTML in a plain chat bubble. The previous Markdown formatter did not support fenced code blocks.

- `WorkspaceCodeBlocks` parses complete fenced blocks for display only. Malformed and unclosed fences remain text. The original assistant reply is preserved in conversation storage, full-reply Copy, Retry, and provider context.
- `WorkspaceActivity.renderChat` displays native code cards interleaved with Markdown prose for normal assistant replies, preserving the existing story/script card path.
- Each card has a language label, lightweight native syntax highlighting, horizontal scrolling and **Copy** of code content alone without Markdown fences or surrounding explanation.
- Complete single-file HTML (maximum 50,000 characters) offers an explicit **Preview** action. Preview is an ephemeral WebView with network, file and content access blocked, DOM storage disabled, navigation denied and no JS bridge. JavaScript runs only after Preview is tapped to support interactive HTML demonstrations. It does not save or modify Workspace project files, and this is not a general-purpose secure sandbox for hostile code.
- A conditional system hint asks for English programming comments and identifiers, valid CSS comments, complete fenced examples, and explanation outside the code. Provider output can still vary; language/format are instructions, not guaranteed enforcement. The latest user request's UI strings remain as requested.
- JVM tests cover parser prose/code separation, incomplete fences, multiple blocks, preview eligibility, and code prompt scoping.
- The one-shot integration workflow ran `:app:testDebugUnitTest :app:assembleDebug` successfully before its code commit `7678a031636dedd1eb6cbf3f93b24f2a2798f0e5`. This documentation push triggers an independent standard Android CI and APK release for its resulting exact commit. Real phone testing is not claimed.

No model/provider switching, keys, Memory, Gemini Voice, Workspace Safe Edit or `main` were intentionally changed by this code-card patch.