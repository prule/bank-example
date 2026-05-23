create table PUBLIC.ACCOUNTS
(
    BALANCE        NUMERIC(38, 2)                                            not null,
    VERSION        BIGINT,
    ID             UUID                                                      not null
        primary key,
    ACCOUNT_NUMBER CHARACTER VARYING(255)                                    not null
        unique,
    STATUS         ENUM ('ACTIVE', 'CREDIT', 'CLOSED', 'DEBIT', 'SUSPENDED') not null
);

create table PUBLIC.JOURNAL_ENTRIES
(
    TIMESTAMP   TIMESTAMP WITH TIME ZONE not null,
    ID          UUID                     not null
        primary key,
    DESCRIPTION CHARACTER VARYING(255)   not null,
    STATUS      ENUM ('FAILED', 'PENDING', 'VERIFIED')
);

create table PUBLIC.LEDGER_TRANSACTIONS
(
    AMOUNT           NUMERIC(38, 2)                                            not null,
    ID               BIGINT auto_increment
        primary key,
    ACCOUNT_ID       UUID                                                      not null,
    JOURNAL_ENTRY_ID UUID,
    TYPE             ENUM ('ACTIVE', 'CREDIT', 'CLOSED', 'DEBIT', 'SUSPENDED') not null,
    constraint FKTA8OSYW941XITVLFME5DQQ5EP
        foreign key (JOURNAL_ENTRY_ID) references PUBLIC.JOURNAL_ENTRIES
);

create table PUBLIC.SYSTEM_CONFIG
(
    ID                    BIGINT not null
        primary key,
    LAST_BALANCE_CHECK_ID BIGINT not null
);

