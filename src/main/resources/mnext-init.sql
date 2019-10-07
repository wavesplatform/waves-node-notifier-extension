create table if not exists mined_blocks (
    height int4 primary key not null,
    reward int8 not null
);

create table if not exists payouts (
  id int4 primary key auto_increment not null,
  from_height int4 not null,
  to_height int4 not null,
  reward int8 not null,
  generating_balance int8 not null,
  active_leases array not null,
  tx_id char(44),
  tx_height int4,
  confirmed bool not null default(false)
);
