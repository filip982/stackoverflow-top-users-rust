# StackOverflow Users App — Project Spec

> **Note:** This is the original requirements brief and is preserved unchanged for traceability.
> Several technical decisions have been deliberately upgraded from what this spec mandates.
> See [`docs/architecture.md`](architecture.md) for the authoritative implementation decisions
> (Rust shared core via UniFFI, SwiftUI + Compose apps, MVI pattern, XcodeGen).

## Overview
An iOS app that fetches and displays StackOverflow users, with follow/unfollow, a detail view, and sorting.

## Functional Requirements

### List Screen
- On launch, show a list of the top 20 StackOverflow users.
- Each cell shows: profile image, name, reputation.
- Each cell has a follow/unfollow option.
  - Follow is simulated locally — no API call.
  - Followed users show an indicator in the list.
  - Follow status persists between sessions.
- If the server is unavailable (offline, error response, etc.), show an empty state with an error message.
- Tapping a cell presents a detail view for that user.

### Detail Screen
Shows:
- Profile picture
- Name
- Reputation
- Follow status, with a follow/unfollow toggle
- Location
- Website URL (if available)

### Stretch Goal — Sorting
Allow sorting via a sort options screen with a radio group (single selection):
- Reputation (`reputation`) — default
- Name (`display_name`)
- Date created (`creation_date`)
- Date updated (`last_modified_date`)

Plus ascending/descending toggle, and Apply/Cancel actions.

## Wireframes

**User Details**
```
┌─────────────────────────────┐
│        USER DETAILS         │
├─────────────────────────────┤
│      ┌───────────┐          │
│      │     ◯     │          │
│      │    /│\    │          │
│      └───────────┘          │
│                             │
│   NAME                      │
│   REPUTATION                │
│   LOCATION                  │
│   WEBSITE URL               │
│                             │
│   ┌─────────────────────┐   │
│   │   FOLLOW/UNFOLLOW   │   │
│   └─────────────────────┘   │
└─────────────────────────────┘
```

**Sort Options**
```
┌─────────────────────────────┐
│        SORT OPTIONS         │
├─────────────────────────────┤
│   ☐ REPUTATION              │
│   ☐ NAME                    │
│   ☐ DATE CREATED            │
│   ☐ DATE UPDATED            │
│                             │
│   ┌──────────┬──────────┐   │
│   │ASCENDING │DESCENDING│   │
│   └──────────┴──────────┘   │
│                             │
│   ┌─────────────────────┐   │
│   │       APPLY         │   │
│   └─────────────────────┘   │
│   ┌─────────────────────┐   │
│   │       CANCEL        │   │
│   └─────────────────────┘   │
└─────────────────────────────┘
```

## Technical Specifications
- Swift only — no Objective-C.
- UIKit for views.
- No 3rd party frameworks.
- Emphasize testability and architecture — any pattern is fine, be ready to justify it.
- All new and existing functionality covered by unit tests.
- Deliver as a Git repo (GitHub/Bitbucket preferred) with commit history.
- Include a README covering app behavior, install requirements, and key technical decisions.

## API Reference

`GET http://api.stackexchange.com/2.2/users?page=1&pagesize=20&order=desc&sort=reputation&site=stackoverflow`

Sort params:
- `reputation`
- `creation_date`
- `display_name`
- `last_modified_date`

### Example Response (truncated)
```json
{
  "items": [
    {
      "badge_counts": { "bronze": 9255, "silver": 9202, "gold": 877 },
      "account_id": 11683,
      "is_employee": false,
      "last_modified_date": 1711287919,
      "last_access_date": 1711355649,
      "reputation_change_year": 13860,
      "reputation_change_quarter": 13860,
      "reputation_change_month": 3856,
      "reputation_change_week": 118,
      "reputation_change_day": 30,
      "reputation": 1454978,
      "creation_date": 1222430705,
      "user_type": "registered",
      "user_id": 22656,
      "accept_rate": 86,
      "location": "Reading, United Kingdom",
      "website_url": "http://csharpindepth.com",
      "link": "https://stackoverflow.com/users/22656/jon-skeet",
      "profile_image": "https://www.gravatar.com/avatar/6d8ebb117e8d83d74ea95fbdd0f87e13?s=256&d=identicon&r=PG",
      "display_name": "Jon Skeet"
    }
  ]
}
```

### Response Schema
```json
{
  "items": [
    {
      "badge_counts": { "bronze": "String", "silver": "String", "gold": "Int" },
      "account_id": "Int",
      "is_employee": "Boolean",
      "last_modified_date": "Int",
      "last_access_date": "Int",
      "reputation_change_year": "Int",
      "reputation_change_quarter": "Int",
      "reputation_change_month": "Int",
      "reputation_change_week": "Int",
      "reputation_change_day": "Int",
      "reputation": "Int",
      "creation_date": "Int",
      "user_type": "String",
      "user_id": "Int",
      "accept_rate": "Int",
      "location": "String?",
      "website_url": "String?",
      "link": "String",
      "profile_image": "String?",
      "display_name": "String"
    }
  ]
}
```

Full API docs: https://api.stackexchange.com/docs/types/user
