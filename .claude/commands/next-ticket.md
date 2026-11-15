---
description: Pull the next Ready ticket from the GitHub board and implement it
argument-hint: "[issue-number]"
---
Work one ticket from the "Dev Board" GitHub Project (owner: TheEmpatheticProgrammer) for the repo TheEmpatheticProgrammer/shelfscan. Use the github MCP tools for everything on GitHub.

## 1. Pick the ticket
- If an issue number was given (`$ARGUMENTS`), use that issue.
- Otherwise list the items on the Dev Board and keep open issues from this repo whose Status is **Ready** and that do not have the `needs-info` label. Choose by priority label (p1, then p2, then p3, then unlabeled), then by oldest.
- If nothing is Ready, say so and stop.

## 2. Check that it's ready
Read the issue and all of its comments. If the acceptance criteria are missing, contradict each other, or leave a decision that would change the design:
- Post a comment on the issue with numbered, specific questions.
- Add the `needs-info` label and set Status to **Backlog**.
- Stop and tell me what you asked.

## 3. Claim it
Assign the issue to me, set Status to **In Progress**, and comment "Picked up, working on it."

## 4. Implement
- `git fetch`, then create a branch from the latest `origin/main`: `issue-<number>-<short-slug>`.
- Follow the existing structure and conventions; read the README and nearby code first. Keep the change scoped to the ticket and its "Out of scope" section.
- Add or update tests for the behavior you changed.
- Backend changes: from `backend/`, run `pytest -q`.
- Android changes: from `android/`, run `.\gradlew.bat testDebugUnitTest assembleDebug`.
- Do not move on until the checks pass. If they won't pass for a reason outside the ticket, stop and tell me.

## 5. Hand off
- Commit with a message that references the issue (`#<number>`), push the branch, and open a PR against `main`. The PR body must contain `Closes #<number>`, a short summary of the change, how each acceptance criterion is met, and the test commands you ran.
- Set the issue's Status to **In Review** and comment with the PR link.
- Never merge the PR. Finish by giving me the PR link and anything a reviewer should look at closely.
