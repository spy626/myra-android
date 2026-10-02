# LYRA native rich-answer presentation acceptance

Date: 2026-10-02. Scope: feature branch agent/myra-phase-1 only.

## Source evidence
The physical-phone comparison in 1000063460.mp4 (LYRA #3400) and 1000063469.mp4 (ChatGPT) shows the same planning intent but different presentation. LYRA has a dense, recurring Kahan/Kya/Result layout and premature builder/backend suggestions; ChatGPT uses contextual sections, compact lists and differentiated tool information. This is an observed PHONE FAIL for #3400, not a typographic 17sp regression.

## Narrow correction
- WorkspaceRichAnswerBlocks: display-only, bounded block parser. It preserves model-authored headings, paragraph content, numbered steps, bullets (including depth), quote, divider and 2–3-column Markdown table rows; safe inline 1/2/3 unfolding only for consecutive explicit numeric markers. It does not manufacture facts, logos, links, recommendations or tool actions.
- WorkspaceRichAnswerView: actual separate Android TextView/LinearLayout rows instead of only a single giant Spannable TextView. Assistant 17sp/user 16sp settings remain unchanged. Verified GitHub URLs reuse WorkspaceMarkdownText native URLSpan rules. Table comparison rows stack on narrow displays; no WebView or remote image fetch.
- WorkspaceActivity: formatted assistant prose in CHAT is now routed through the block renderer. Plain casual replies, user bubbles, story cards and code cards retain their established paths; mixed code/prose uses rich view only for prose segments.
- WorkspaceChatGateway / WorkspacePracticalPlanningGuide: context-driven structure rather than canned fields; advice-only must not become signup, project initialization, backend setup or code. The reply's semantic choice is still the current approved free provider, not a second planner or model.
- Unit tests cover mixed Markdown, malformed pipes, safe links, casual chat, nested bullets, large replies and weak-model inline numbered steps.

## Acceptance boundary
Successful unit tests, lint and APK signing only establish CI GREEN. On a real phone, compare different questions: 3-step planning-only, a general comparison, a mixed long explanation and one casual greeting. The content should adapt to each request, with distinct native rows where appropriate and no repetitive Kahan/Kya/Result form. Only physical testing can establish PHONE PASS.
