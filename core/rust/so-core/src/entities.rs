/// Stack Overflow user id (`user_id` on the wire).
pub type UserId = u64;

/// Domain user. Dates are Unix epoch seconds.
#[derive(Debug, Clone, PartialEq, Eq, Hash, uniffi::Record)]
pub struct User {
    pub id: UserId,
    pub display_name: String,
    pub reputation: u64,
    pub avatar_url: Option<String>,
    pub location: Option<String>,
    pub website_url: Option<String>,
    pub creation_date: i64,
    pub last_modified_date: Option<i64>,
}

/// Client-side sort keys offered by the sort options screen.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub enum SortField {
    #[default]
    Reputation,
    Name,
    CreationDate,
    ModifiedDate,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub enum SortDirection {
    Asc,
    #[default]
    Desc,
}
