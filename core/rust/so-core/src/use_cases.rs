//! The three business use cases. Kept intentionally small.

use std::cmp::Ordering;
use std::sync::Arc;

use crate::{CoreError, SortDirection, SortField, User, UserId, UserRepository};

/// Fetches the top users, returned in the default order (reputation desc).
#[derive(Clone)]
pub struct GetTopUsers {
    repository: Arc<UserRepository>,
}

impl GetTopUsers {
    pub fn new(repository: Arc<UserRepository>) -> Self {
        Self { repository }
    }

    pub async fn execute(&self) -> Result<Vec<User>, CoreError> {
        let users = self.repository.top_users().await?;
        Ok(SortUsers.execute(users, SortField::default(), SortDirection::default()))
    }
}

/// Flips the follow state of a user; returns the new state (`true` = followed).
#[derive(Clone)]
pub struct ToggleFollow {
    repository: Arc<UserRepository>,
}

impl ToggleFollow {
    pub fn new(repository: Arc<UserRepository>) -> Self {
        Self { repository }
    }

    pub async fn execute(&self, id: UserId) -> Result<bool, CoreError> {
        self.repository.toggle_follow(id).await
    }
}

/// Client-side sort. Deterministic: ties are broken by `id` ascending and
/// missing values (`last_modified_date: None`) always sort last, regardless of
/// direction. Names compare case-insensitively.
#[derive(Debug, Clone, Copy, Default)]
pub struct SortUsers;

impl SortUsers {
    pub fn execute(
        &self,
        users: Vec<User>,
        field: SortField,
        direction: SortDirection,
    ) -> Vec<User> {
        let mut users = users;
        users.sort_by(|a, b| {
            let primary = match field {
                SortField::Reputation => directed(a.reputation.cmp(&b.reputation), direction),
                SortField::Name => directed(
                    a.display_name
                        .to_lowercase()
                        .cmp(&b.display_name.to_lowercase()),
                    direction,
                ),
                SortField::CreationDate => {
                    directed(a.creation_date.cmp(&b.creation_date), direction)
                }
                SortField::ModifiedDate => match (a.last_modified_date, b.last_modified_date) {
                    (Some(x), Some(y)) => directed(x.cmp(&y), direction),
                    (Some(_), None) => Ordering::Less,
                    (None, Some(_)) => Ordering::Greater,
                    (None, None) => Ordering::Equal,
                },
            };
            primary.then_with(|| a.id.cmp(&b.id))
        });
        users
    }
}

fn directed(ordering: Ordering, direction: SortDirection) -> Ordering {
    match direction {
        SortDirection::Asc => ordering,
        SortDirection::Desc => ordering.reverse(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn user(id: UserId, name: &str, rep: u64, created: i64, modified: Option<i64>) -> User {
        User {
            id,
            display_name: name.into(),
            reputation: rep,
            avatar_url: None,
            location: None,
            website_url: None,
            creation_date: created,
            last_modified_date: modified,
        }
    }

    fn ids(users: &[User]) -> Vec<UserId> {
        users.iter().map(|u| u.id).collect()
    }

    fn sample() -> Vec<User> {
        vec![
            user(5, "bob", 100, 50, Some(500)),
            user(2, "Alice", 300, 10, None),
            user(9, "carol", 100, 30, Some(900)),
            user(1, "alice", 200, 30, Some(500)),
            user(7, "Dave", 300, 20, None),
        ]
    }

    #[test]
    fn reputation_desc_ties_broken_by_id_asc() {
        let sorted = SortUsers.execute(sample(), SortField::Reputation, SortDirection::Desc);
        assert_eq!(ids(&sorted), vec![2, 7, 1, 5, 9]);
    }

    #[test]
    fn reputation_asc_ties_still_broken_by_id_asc() {
        let sorted = SortUsers.execute(sample(), SortField::Reputation, SortDirection::Asc);
        assert_eq!(ids(&sorted), vec![5, 9, 1, 2, 7]);
    }

    #[test]
    fn name_is_case_insensitive_with_id_tiebreak() {
        let asc = SortUsers.execute(sample(), SortField::Name, SortDirection::Asc);
        assert_eq!(ids(&asc), vec![1, 2, 5, 9, 7]);
        let desc = SortUsers.execute(sample(), SortField::Name, SortDirection::Desc);
        assert_eq!(ids(&desc), vec![7, 9, 5, 1, 2]);
    }

    #[test]
    fn creation_date_both_directions() {
        let asc = SortUsers.execute(sample(), SortField::CreationDate, SortDirection::Asc);
        assert_eq!(ids(&asc), vec![2, 7, 1, 9, 5]);
        let desc = SortUsers.execute(sample(), SortField::CreationDate, SortDirection::Desc);
        assert_eq!(ids(&desc), vec![5, 1, 9, 7, 2]);
    }

    #[test]
    fn modified_date_puts_missing_last_in_both_directions() {
        let asc = SortUsers.execute(sample(), SortField::ModifiedDate, SortDirection::Asc);
        assert_eq!(ids(&asc), vec![1, 5, 9, 2, 7]);
        let desc = SortUsers.execute(sample(), SortField::ModifiedDate, SortDirection::Desc);
        assert_eq!(ids(&desc), vec![9, 1, 5, 2, 7]);
    }

    #[test]
    fn sorting_is_independent_of_input_order() {
        let mut reversed = sample();
        reversed.reverse();
        for field in [
            SortField::Reputation,
            SortField::Name,
            SortField::CreationDate,
            SortField::ModifiedDate,
        ] {
            for dir in [SortDirection::Asc, SortDirection::Desc] {
                assert_eq!(
                    SortUsers.execute(sample(), field, dir),
                    SortUsers.execute(reversed.clone(), field, dir),
                    "{field:?} {dir:?}"
                );
            }
        }
    }

    #[test]
    fn defaults_are_reputation_desc() {
        assert_eq!(SortField::default(), SortField::Reputation);
        assert_eq!(SortDirection::default(), SortDirection::Desc);
    }
}
