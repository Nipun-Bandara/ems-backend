<!--
Title this PR as a Conventional Commit — the commitlint check reads it:

    <type>(<scope>): <subject>

Scopes: gateway | identity | common | build | ci
-->

## What changed

<!-- A short description of the change and why it was needed. -->

## Item

<!-- The plan item this PR implements, e.g. "Item 7". Write "none" for
     unplanned work and say what prompted it. -->

Item:

## Verified by

<!-- Copy the Verify steps from the item above, one checkbox each, and tick
     only the ones you actually ran. Paste output or a screenshot where the
     step produces something worth seeing. -->

- [ ]
- [ ]
- [ ]

### CI gates

- [ ] `mvn -B clean verify` passes locally (this runs the Spotless check)
- [ ] PR title is a Conventional Commit with one of the scopes above

## Notes for the reviewer

<!-- Anything that needs a second opinion: trade-offs taken, things left out,
     follow-up work. Delete if there is none. -->
