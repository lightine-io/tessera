# How we work on Tessera

The rules for day-to-day work. Short on purpose: what to do, not why.
The why (the Kanban Method, built step by step) is on the board issue TES-215. Where the two differ, this guide wins.
In use since 2026-10-31; the rollout continues under TES-215.

## Roles

| Role | Who today | Does |
|---|---|---|
| Owner | The maintainer | Triages New, orders Options, refills Next, rejects items, confirms vulnerabilities, makes decisions, does device QA on real devices, pushes the signed release tag |
| Worker | The maintainer, or one AI session | Pulls from Next and takes the item to Done |
| Flow manager | The owner; at each Kanban meeting the worker in the session runs it | Watches the flow: aging, blocked items, limits |

- Each worker has their own Develop slot. Each AI session counts as one worker. Only one AI session works board items at a time, unless the owner starts more.
- With a second person: the card names the assignee; a pull request is reviewed by someone other than its author. The owner role stays with one person. The limits on Next, Review and Test stay board-wide.
- "Tell the owner" means: one sentence in the current session; if the owner is not in the session, a comment on the card.

## The board

YouTrack project TES, board TES. GitHub shows the code and the pull requests; CI moves cards when a pull request opens, merges or ships.

| Column | Holds | WIP limit | Leave when | Goes to |
|---|---|---|---|---|
| New | Anything, one raw line, untriaged | none | The owner triages it | Options or Rejected |
| Options | Triaged, not promised. Field "Goal": 1.0 or Later | none | The owner pulls it into Next | Next or Rejected |
| Next | Committed, in order. The entry to Next is the commitment point | 3 | A worker takes it | Develop |
| Develop | In progress | 1 per worker | A pull request is open and its checklist is done (a Decision: recorded) | Review (a Decision: Done) |
| Review | Pull request open | 2 | Required checks are green and it is merged | Test if labelled `device-qa`; else Staging if it ships to users; else Done |
| Test | Merged, needs device QA | 2 | Device QA passed and is recorded on the card | Staging |
| Staging | Merged, waits for a release | 8 | Released | Done |
| Done | Delivered: released (ships to users), merged (ships nothing), or recorded (a decision) | | | |
| Rejected | Not doing it, with a reason. Only the owner rejects | | | |

WIP limits are first guesses; change them at a review. Blocked cards still count toward their column's limit. When Staging reaches 8, plan a release.

## The card

Type, service (SDK, vulnerability response, questions and bug reports, contributions), class of service, goal, platform, assignee, area (optional); a due date for Fixed Date items; the `blocked` tag with the reason in a comment; a "depends on" link when it waits for other work; a failure-demand flag when the work exists only because earlier work was wrong or incomplete; "Fixed in" (set by CI). The board shows how many days each card has been in its column.

## Start of every session (Kanban meeting)

1. Read the board from right to left: Staging, Test, Review, Develop, Next.
2. For each item, ask: what keeps it from moving? Clear that first. Raise any item that has been more than 14 days in Next, Develop, Review or Test.
3. Read open GitHub issues, outside pull requests and Dependabot alerts. Each new one becomes a card in New with a link back (security reports: see Vulnerabilities). Reply on GitHub within 7 days; close the GitHub issue when its card is Done or Rejected.
4. If Next holds fewer than 3 items, tell the owner.
5. Only then pull new work.

If the owner is not in the session, the worker holds the meeting alone and leaves blockers as comments on the cards.

## Pulling work

1. Expedite first (top lane).
2. Then Fixed Date items in Next, nearest date first.
3. Then Standard, from the top of Next.
4. Intangible items are pulled when no Standard item is in Next, and at least one in every five items pulled into Develop is Intangible (count the last five pulls; expedite items and blockers do not count as pulls). This rule may put an Intangible item ahead of Standard.

The owner fills Next from Options for the goal 1.0 first; items with the goal Later are pulled only when no 1.0 item is left in Options.

Finish before starting. Pull only into a free Develop slot, except for the two cases marked "over the limit".

If Next is empty: tell the owner, clear blockers, and stop. Do not pull from Options yourself.

## Classes of service

| Class | When | What to do |
|---|---|---|
| Expedite | Users are harmed now: a broken release, or a vulnerability the owner has confirmed | Over the limit: start at once, pause current work (mark it blocked "paused for expedite"). One expedite at a time: a second one waits at the top of the lane, the owner sets the order, and the first is never paused for it. Merging an expedite fix triggers a release at once. Targets: a critical defect released within 2 weeks; a vulnerability per the section below |
| Fixed Date | A date after which it is too late: dependency upgrades (1 April, 1 October), new Android, iOS or Xcode versions, credential rotation | The card carries the due date. The notice is the day the card is created: at the strategy review for known dates, when CI opens the dependency-upgrade issue, or when a vendor announces a version. On notice the owner puts the card into Next. It must be done before the due date. A missed date is raised at the next review; it stays Fixed Date |
| Standard | Needed for the current goal | Top of Next first |
| Intangible | Value not visible at once: upkeep with no date, process improvement, cleanup, documentation polish | The one-in-five rule above |

## During work

- **A finding that does not block the current item:** write one line in New, tell the owner, carry on.
- **A finding that blocks the current item:** create its own item and put it in Develop (over the limit by one). Mark the current item blocked with the reason. Finish the blocker, then go back.
- **A correction from the owner about the current item:** apply it now, and note it as a comment on the card.
- **A correction about how we work in general:** follow it for the rest of the session, and write it in New as a process improvement (class Intangible). It binds later sessions only once it is in this guide.
- **Blocked by something outside, including waiting for the owner:** mark the card blocked with the reason and tell the owner. It keeps its slot. If your slot is full and everything you hold is blocked, stop and say so; do not pull more.
- **Waiting for other work** (for example a tessera-swift change, a main-repo release, or an outside change): add a "depends on" link and the `blocked` tag with the reason.
- Move the card when the work happens, not later.

## Device QA

- Device QA on a real device is done by the owner. An AI session may run checks on emulators and simulators and records them on the card, but they do not replace the device check.
- A pull request that needs device QA gets the `device-qa` label; after the merge it sits in Test.
- No device available: the item stays in Test, marked blocked.
- Test is full (2): do not merge more `device-qa` pull requests until one leaves Test.
- Merged code ships with the next release even if its card is still in Test. So a release goes out only when Test is empty, or when the owner accepts the open Test cards in writing on the release card.

## Decisions

A Decision item (class Standard) leaves Next when a worker takes it. In Develop: write the options and a recommendation on the card, mark it blocked "waiting for the owner", and tell the owner. The owner decides, at the latest at the next Kanban meeting the owner attends. Record the decision (an ADR, architecture decision record, in the internal KB), then Done. No pull request.

## Releases

- Decide to release (delivery planning) when a goal is reached, when Staging reaches 8 items, when 10 weeks have passed with no release (the limit is 3 months), or at once after an expedite fix is merged.
- Check Test first (see Device QA).
- The owner pushes the signed tag. The release workflow publishes. CI sets "Fixed in" on every released card and moves it to Done.
- Public KB pages for the release are published by the owner at the release.

## Vulnerabilities

Follow SECURITY.md. Reports arrive as private GitHub advisories. Acknowledge within 5 business days; assess and set the severity within 10 business days; fix a medium or higher issue within 60 days. The owner confirms a report. Until a fix ships, the details stay in the private advisory; the card on the board carries no exploit detail and nothing goes to a public GitHub issue. The fix itself is Expedite.

Dependency alerts (Dependabot) are read at the Kanban meeting. An alert in a shipped dependency becomes a Fixed Date card due 60 days after the alert; one in a build-only or test-only dependency is Intangible, or dismissed with a recorded reason.

## Reviews

| Review | When | Covers |
|---|---|---|
| Service delivery review and retrospective | After every release | The flow: lead time from Next to Done, items delivered, WIP, blocked and aging items, and the blocked reasons counted by kind; the fitness thresholds (design, step 1); the health indicators, counted from the board: share of PRs that are process work (30% or less), share of new issues that are failure demand (20% or less), stage changes recorded on the day (90% or more), owner corrections per session from the card comments (0 to 1); what to change in these rules. It ends with one concrete action to improve the flow, written in New |
| Strategy review | When a goal is reached | The next goal; the Fixed Date cards for the coming months |

## Changing these rules

Anyone may propose a change at any time: write it in New as a process improvement. A change takes effect when the owner accepts it and it is written into this guide; it is checked at the next review.

## Words

- **WIP**: work in progress; the limit on how many items a column holds.
- **Commitment point**: the entry to Next. Before it nothing is promised.
- **Triage**: deciding where a New item goes: Options or Rejected.
- **Blocked**: a card that cannot move; it carries the reason in a comment.
- **Paused**: blocked because an expedite item took the worker.
- **Fits**: the first item in the pull order that is in Next and not blocked.
- **Process work**: work on the way of working (rules, CI, board, AI setup), not on the product.
- **Expedite lane**: a row across the top of the board for Expedite items.
- **ADR**: architecture decision record, one page per decision in the internal KB.
- **`device-qa`**: the pull request label for work that needs a check on a real device.
- **`no-changelog`**: the pull request label for work that ships nothing to users.
- **Fixed in**: the card field with the release that shipped the item; CI sets it.
- **Failure demand**: work that exists only because earlier work was wrong or incomplete.
