/*
 *  Kontalk Java client
 *  Copyright (C) 2014 Kontalk Devteam <devteam@kontalk.org>
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.kontalk.system;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.kontalk.misc.JID;
import org.kontalk.model.chat.ChatList;
import org.kontalk.model.Contact;
import org.kontalk.model.chat.GroupChat;
import org.kontalk.model.chat.GroupChat.KonGroupChat;
import org.kontalk.model.chat.GroupMetaData.KonGroupData;
import org.kontalk.model.chat.Member;
import org.kontalk.model.message.MessageContent;
import org.kontalk.model.message.MessageContent.GroupCommand;

/**
 *
 * @author Alexander Bikadorov {@literal <bikaejkb@mail.tu-berlin.de>}
 */
final class GroupControl {
    private static final Logger LOGGER = Logger.getLogger(GroupControl.class.getName());

    private final Control mControl;

    GroupControl(Control control) {
        mControl = control;
    }

    abstract class ChatControl<C extends GroupChat> {

        protected final C mChat;

        private ChatControl(C chat) {
            mChat = chat;
        }

        abstract void onCreate();

        abstract void onSetSubject(String subject);

        abstract boolean beforeDelete();

        //
        abstract void onInMessage(GroupCommand command, Contact sender);
    }

    final class KonChatControl extends ChatControl<KonGroupChat> {

        private KonChatControl(KonGroupChat chat) {
            super(chat);
        }

        @Override
        void onCreate() {
            // send create group command
            List<JID> jids = mChat.getValidContacts().stream()
                    .map(contact -> contact.getJID())
                    .collect(Collectors.toList());

            mControl.createAndSendMessage(mChat,
                    MessageContent.groupCommand(
                            MessageContent.GroupCommand.create(
                                    jids,
                                    mChat.getSubject())
                    )
            );
        }

        @Override
        public void onSetSubject(String subject) {
            if (!mChat.isAdministratable()) {
                LOGGER.warning("not admin");
                return;
            }

            mControl.createAndSendMessage(mChat, MessageContent.groupCommand(
                    GroupCommand.set(subject)));

            mChat.setSubject(subject);
        }

        @Override
        public boolean beforeDelete() {
            if (!mChat.isValid())
                return true;

            // note: group chats are not 'deleted', were just leaving them
            return mControl.createAndSendMessage(mChat,
                    MessageContent.groupCommand(GroupCommand.leave()));
        }

        @Override
        public void onInMessage(GroupCommand command, Contact sender) {
            // TODO ignore message if it contains unexpected group command (?)

            // NOTE: chat was selected/created by GID so we can be sure message
            // and chat GIDs match
            KonGroupData gid = mChat.getGroupData();
            MessageContent.GroupCommand.OP op = command.getOperation();

            // validation check
            if (op != MessageContent.GroupCommand.OP.LEAVE) {
                // sender must be owner
                if (!gid.owner.equals(sender.getJID())) {
                    LOGGER.warning("sender not owner");
                    return;
                }
            }

            if (op == MessageContent.GroupCommand.OP.CREATE ||
                    op == MessageContent.GroupCommand.OP.SET) {
                // add contacts if necessary
                // TODO design problem here: we need at least the public keys, but user
                // might dont wanna have group members in contact list
                command.getAdded().stream().forEach(jid -> {
                    boolean succ = mControl.getOrCreateContact(jid).isPresent();
                    if (!succ)
                        LOGGER.warning("can't create contact, JID: "+jid);
                });
            }

            mChat.applyGroupCommand(command, sender);
        }
    }

    ChatControl getInstanceFor(GroupChat chat) {
        if (chat instanceof KonGroupChat)
                return new KonChatControl((KonGroupChat) chat);
        throw new IllegalArgumentException("Not implemented for "+chat);
    }

    static KonGroupData newKonGroupData(JID myJID) {
        return new KonGroupData(myJID,
                org.jivesoftware.smack.util.StringUtils.randomString(8));
    }

    static Optional<GroupChat> getGroupChat(MessageContent content, Contact sender) {
        KonGroupData gData = content.getGroupData().orElse(null);
        if (gData == null) {
            LOGGER.warning("message does not contain group data");
            return Optional.empty();
        }

        ChatList chatList = ChatList.getInstance();

        // get old...
        GroupChat chat = chatList.get(gData).orElse(null);
        if (chat != null) {
            if (!chat.getAllContacts().contains(sender)) {
                LOGGER.warning("chat does not include sender: "+chat);
                // TODO we should ask owner to confirm member list
                return Optional.empty();
            }
            return Optional.of(chat);
        }

        // ...or create new
        if (!gData.owner.equals(sender.getJID())) {
            LOGGER.warning("sender is not owner for new group chat: "+gData);
            return Optional.empty();
        }

        GroupCommand command = content.getGroupCommand().orElse(null);
        if (command == null || !command.isAddingMe()) {
            LOGGER.warning("ignoring unexpected message of unknown group");
            return Optional.empty();
        }

        return Optional.of(
                chatList.create(
                        Arrays.asList(new Member(sender, Member.Role.OWNER)),
                        gData));
    }
}