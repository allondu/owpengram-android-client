# Secret Chat Only — backend design

## Status

The Android app currently implements **Secret Chat Only** as a client-side demo:

- A warning is shown before the mode is enabled.
- Every normal private chat is redirected to an existing secret chat, or a new secret chat is started automatically.
- Groups, channels and bots are blocked while the mode is active.
- The sender-side notification described below is **not implemented**. It needs the backend work in this document.

This is not a security boundary. A modified client, an official Telegram client, or the Bot API can still send normal messages to the account. Server-side enforcement is required before this can be treated as a privacy guarantee.

## Goal

When a user enables Secret Chat Only, people who try to message that user should be told:

> This user only accepts secret chats. Start a secret chat to continue.

The recipient should not receive normal messages while the mode is active. Existing secret chats remain usable.

## Recommended server model

Store the preference on the user record:

```json
{
  "user_id": 123456789,
  "privacy": {
    "secret_chat_only": true,
    "secret_chat_only_updated_at": "2026-11-19T12:00:00Z"
  }
}
```

Only the authenticated account may change its own preference. Do not expose the flag to unrelated users through the public profile API unless the product explicitly wants it visible.

## API

### Read the current preference

`GET /v1/me/privacy/secret-chat-only`

Response:

```json
{
  "enabled": true,
  "updated_at": "2026-11-19T12:00:00Z"
}
```

### Update the preference

`PUT /v1/me/privacy/secret-chat-only`

Request:

```json
{
  "enabled": true
}
```

Response: `204 No Content` or the updated object from the read endpoint.

Requirements:

- Require a valid authenticated session.
- Use idempotency keys for retries.
- Rate-limit changes (for example, 10 changes per hour per account).
- Audit-log the change with the account id, timestamp, IP and client version.

### Check before delivering a normal message

The message-send path must check the recipient's preference **before** accepting a normal message:

`POST /v1/messages` (existing endpoint)

If the recipient has `secret_chat_only = true`, reject the normal message with:

```json
{
  "error": {
    "code": "SECRET_CHAT_ONLY",
    "message": "This user only accepts secret chats. Start a secret chat to continue.",
    "recipient_id": 123456789,
    "secret_chat_required": true
  }
}
```

HTTP status: `403 Forbidden` or `422 Unprocessable Entity`, whichever fits the existing API conventions.

The client should show the returned `message` to the sender and, where possible, offer a **Start secret chat** action. Never silently drop a message without telling the sender.

### Secret chat handshake

The encrypted-chat handshake itself must remain allowed. Check the privacy flag only for ordinary message delivery, not for the `requestEncryption` / secret-chat setup flow.

## Delivery and notification behavior

When a normal message is rejected:

1. Do not create a message row for the recipient.
2. Return the `SECRET_CHAT_ONLY` error to the sender.
3. Optionally create a one-time system notification for the sender:
   - title: `Secret chats only`
   - body: `This user only accepts secret chats. Start a secret chat to continue.`
   - deep link: open the recipient's profile, not a normal chat.
4. Do not notify the recipient about blocked normal messages. Notifications about every blocked attempt would create a spam and harassment vector.

If the product wants a recipient-side audit trail, expose it only in privacy settings and aggregate it, not as per-message push notifications.

## Compatibility with existing chats

- Existing **secret chats** continue to work.
- Existing **normal chats** should become read-only from the sender's perspective while the mode is active. The server can return `SECRET_CHAT_ONLY` on new sends.
- The recipient should still be able to read old normal-chat history, unless the product also chooses to hide it.
- Incoming normal messages from before the mode was enabled should not be deleted automatically.

## Client integration points

The current app is a demo. A complete client implementation should:

1. Add a `SecretChatOnlyController` that owns the remote preference and syncs it on login and app start.
2. Keep the local `SharedConfig.secretChatsOnly` flag only as a cached UI hint; treat the server response as authoritative.
3. Handle `SECRET_CHAT_ONLY` errors in `SendMessagesHelper` and show the server-provided message.
4. Add a **Start secret chat** action on the error sheet.
5. Keep the existing local gate in `ChatActivity` as a fast path, but never rely on it for enforcement.
6. Add a settings sync state: `Off`, `On`, `Syncing`, `Failed — retry`.

## Tests

- Enable the mode, then attempt a normal message from another account → `SECRET_CHAT_ONLY`, no message row.
- Attempt a normal message from a group → rejection behavior is defined (the mode applies to private chats only unless the product says otherwise).
- Start a secret chat while the mode is active → handshake succeeds.
- Send in an existing secret chat → succeeds.
- Disable the mode → normal messages succeed again.
- Two accounts cannot read or change each other's preference.
- Repeated updates are idempotent and rate-limited.
- Sender receives a clear error and the recipient receives no blocked-message push spam.

## Rollout

1. Ship the read/write preference API behind a feature flag.
2. Add server-side enforcement in shadow mode: log what would have been rejected without rejecting.
3. Enable enforcement for internal accounts.
4. Enable for all accounts.
5. Only then advertise the feature in the Android app as a real privacy mode.

## Security notes

- The flag is not a substitute for end-to-end encryption. Secret chats are encrypted; normal cloud chats are not.
- Do not leak the flag through search, presence, contact-discovery or public profile endpoints.
- Do not let a sender bypass the check by using a different message type, scheduled message, inline bot, or a stale cached chat.
- Keep the rejection message generic enough not to reveal more than the recipient chose to share.
