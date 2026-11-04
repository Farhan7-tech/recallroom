package in.yusr.recallroom.switchapi;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@code message} event from the agent stream.
 *
 * @param sender     the participant id Switch assigns ("@switch-mattermost-…-user:localhost"). It is tied to
 *                   the account on the messaging app, not its display name, so it is what approvals check.
 * @param onBehalfOf set when an agent posted for someone else; such a message never counts as that person
 * @param senderRole the sender's relation to this agent, e.g. "owner"
 */
public record RoomMessage(
        String roomId,
        String messageId,
        String threadId,
        String sender,
        String senderName,
        String senderKind,
        String onBehalfOf,
        String senderRole,
        String body,
        boolean addressed,
        List<String> attachments) {

    static RoomMessage from(JsonNode data) {
        JsonNode p = data.path("payload");
        List<String> files = new ArrayList<>();
        for (JsonNode a : p.path("attachments")) {
            files.add(a.path("filename").asString(""));
        }
        return new RoomMessage(
                data.path("room_id").asString(null),
                text(p, "message_id"),
                text(p, "thread_id"),
                text(p, "sender"),
                text(p, "sender_name"),
                text(p, "sender_kind"),
                text(p, "on_behalf_of"),
                text(p, "sender_role"),
                p.path("body").asString(""),
                p.path("addressed").asBoolean(false),
                List.copyOf(files));
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isNull() || v.isMissingNode() ? null : v.asString();
    }
}
