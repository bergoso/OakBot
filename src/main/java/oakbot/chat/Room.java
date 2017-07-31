package oakbot.chat;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.websocket.ClientEndpointConfig;
import javax.websocket.ClientEndpointConfig.Configurator;
import javax.websocket.DeploymentException;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;

import oakbot.chat.event.Event;
import oakbot.chat.event.MessageDeletedEvent;
import oakbot.chat.event.MessageEditedEvent;
import oakbot.chat.event.MessagePostedEvent;
import oakbot.chat.event.MessageStarredEvent;
import oakbot.chat.event.MessagesMovedEvent;
import oakbot.chat.event.UserEnteredEvent;
import oakbot.chat.event.UserLeftEvent;
import oakbot.util.ChatUtils;
import oakbot.util.Http;
import oakbot.util.Http.Response;
import oakbot.util.JsonUtils;

/**
 * Represents the connection to a room the user has joined. Use the
 * {@link ChatClient#joinRoom} method to properly create an instance of this
 * class.
 * @author Michael Angstadt
 */
public class Room implements Closeable {
	private static final Logger logger = Logger.getLogger(Room.class.getName());
	private static final int MAX_MESSAGE_LENGTH = 500;

	private final int roomId;
	private final String fkey;
	private final String chatDomain;
	private final boolean canPost;
	private final Http http;
	private final ChatClient chatClient;
	private final Session session;
	private final ObjectMapper mapper = new ObjectMapper();

	private final Map<Class<? extends Event>, List<Consumer<Event>>> listeners;
	{
		Map<Class<? extends Event>, List<Consumer<Event>>> map = new HashMap<>();
		map.put(MessageDeletedEvent.class, new ArrayList<>());
		map.put(MessageEditedEvent.class, new ArrayList<>());
		map.put(MessagePostedEvent.class, new ArrayList<>());
		map.put(MessagesMovedEvent.class, new ArrayList<>());
		map.put(MessageStarredEvent.class, new ArrayList<>());
		map.put(UserEnteredEvent.class, new ArrayList<>());
		map.put(UserLeftEvent.class, new ArrayList<>());
		this.listeners = Collections.unmodifiableMap(map);
	}

	/**
	 * Creates a connection to a specific chat room. This constructor is only
	 * meant to be called by {@link ChatClient} when the user joins a room.
	 * @param roomId the room ID
	 * @param domain the Stack Exchange domain (e.g. "stackoverflow.com")
	 * @param http the HTTP client
	 * @param webSocketContainer the object used to create the web socket
	 * connection
	 * @param chatClient the {@link ChatClient} object that created this
	 * connection
	 * @throws IOException if there's a problem joining the room
	 * @throws RoomNotFoundException if the room does not exist or the user does
	 * not have permission to view the room
	 */
	public Room(int roomId, String domain, Http http, WebSocketContainer webSocketContainer, ChatClient chatClient) throws IOException, RoomNotFoundException {
		this.roomId = roomId;
		chatDomain = "https://chat." + domain;
		this.http = http;
		this.chatClient = chatClient;

		Response response = http.get(chatDomain + "/rooms/" + roomId);

		/*
		 * A 404 response will be returned if the room doesn't exist.
		 * 
		 * A 404 response will also be returned if the room is inactive, and the
		 * user does not have enough reputation/privileges to see inactive
		 * rooms.
		 */
		if (response.getStatusCode() == 404) {
			throw new RoomNotFoundException(roomId);
		}

		String body = response.getBody();
		fkey = ChatUtils.parseFkey(body);
		if (fkey == null) {
			throw new IOException("Could not get fkey of room " + roomId + ".");
		}

		/*
		 * The textbox for sending messages won't be there if the user can't
		 * post to the room.
		 */
		canPost = body.contains("<textarea id=\"input\">");

		/**
		 * Create the web socket connection.
		 */
		{
			String webSocketUrl = getWebSocketUrl();

			//@formatter:off
			ClientEndpointConfig config = ClientEndpointConfig.Builder.create()
				.configurator(new Configurator() {
					@Override
					public void beforeRequest(Map<String, List<String>> headers) {
						headers.put("Origin", Arrays.asList(chatDomain));
					}
				})
			.build();
			//@formatter:on

			logger.info("Connecting to web socket [room=" + roomId + "]: " + webSocketUrl);

			try {
				session = webSocketContainer.connectToServer(new Endpoint() {
					@Override
					public void onOpen(Session session, EndpointConfig config) {
						session.addMessageHandler(String.class, Room.this::handleWebSocketMessage);
					}

					@Override
					public void onError(Session session, Throwable t) {
						logger.log(Level.SEVERE, "Problem with web socket [room=" + roomId + "]. Leaving room.", t);
						leave();
					}
				}, config, new URI(webSocketUrl));
			} catch (DeploymentException | URISyntaxException e) {
				throw new IOException(e);
			}

			logger.info("Web socket connection successful [room=" + roomId + "]: " + webSocketUrl);
		}
	}

	/**
	 * Gets the ID of this room.
	 * @return the room ID
	 */
	public int getRoomId() {
		return roomId;
	}

	/**
	 * Gets this room's fkey. The fkey is a 32 character, hexadecimal sequence
	 * that is required to interact with a chat room in any way. It changes at
	 * every login.
	 * @return the fkey
	 */
	public String getFkey() {
		return fkey;
	}

	private String getWebSocketUrl() throws IOException {
		//@formatter:off
		Response response = http.post(chatDomain + "/ws-auth",
			"roomid", roomId,
			"fkey", fkey
		);
		//@formatter:on

		String url = response.getBodyAsJson().get("url").asText();

		List<ChatMessage> messages = getMessages(1);
		ChatMessage latest = messages.isEmpty() ? null : messages.get(0);
		long time = (latest == null) ? 0 : latest.getTimestamp().toEpochSecond(ZoneOffset.UTC);
		return url + "?l=" + time;
	}

	/**
	 * Handles web socket messages.
	 * @param json the content of the message (formatted as a JSON object)
	 */
	private void handleWebSocketMessage(String json) {
		JsonNode node;
		try {
			node = mapper.readTree(json);
		} catch (IOException e) {
			logger.log(Level.SEVERE, "[room " + roomId + "]: Problem parsing JSON from web socket:\n" + json, e);
			return;
		}

		if (logger.isLoggable(Level.FINE)) {
			logger.fine("[room " + roomId + "]: Received message:\n" + JsonUtils.prettyPrint(node) + "\n");
		}

		JsonNode roomNode = node.get("r" + roomId);
		if (roomNode == null) {
			return;
		}

		JsonNode eventsNode = roomNode.get("e");
		if (eventsNode == null || !eventsNode.isArray()) {
			return;
		}

		Multimap<EventType, JsonNode> eventsByType = ArrayListMultimap.create();
		for (JsonNode eventNode : eventsNode) {
			JsonNode eventTypeNode = eventNode.get("event_type");
			if (eventTypeNode == null || !eventTypeNode.canConvertToInt()) {
				logger.warning("[room " + roomId + "]: Ignoring JSON object that does not have a valid \"event_type\" field:\n" + JsonUtils.prettyPrint(eventNode) + "\n");
				continue;
			}

			EventType eventType = EventType.get(eventTypeNode.asInt());
			if (eventType == null) {
				logger.warning("[room " + roomId + "]: Ignoring event with unknown \"event_type\":\n" + JsonUtils.prettyPrint(eventNode) + "\n");
				continue;
			}

			eventsByType.put(eventType, eventNode);
		}

		List<Event> eventsToPublish = new ArrayList<>();

		eventsToPublish.addAll(EventParsers.reply(eventsByType));
		eventsToPublish.addAll(EventParsers.mention(eventsByType));

		Event movedOut = EventParsers.messagesMovedOut(eventsByType);
		if (movedOut != null) {
			eventsToPublish.add(movedOut);
		}
		Event movedIn = EventParsers.messagesMovedIn(eventsByType);
		if (movedIn != null) {
			eventsToPublish.add(movedIn);
		}

		/**
		 * Sort the remaining event nodes by event ID, just to make sure they
		 * are processed in the same order they were received from the web
		 * socket.
		 */
		List<JsonNode> remainingEventNodes = new ArrayList<>(eventsByType.values());
		Collections.sort(remainingEventNodes, new Comparator<JsonNode>() {
			@Override
			public int compare(JsonNode a, JsonNode b) {
				JsonNode idNode = a.get("id");
				long id1 = (idNode == null) ? 0 : idNode.asLong();

				idNode = b.get("id");
				long id2 = (idNode == null) ? 0 : idNode.asLong();

				/*
				 * Don't just return "id1-id2" because this could result in a
				 * value that won't fit into an integer.
				 */
				return (id1 < id2) ? -1 : (id1 > id2) ? 1 : 0;
			}
		});

		for (JsonNode eventNode : remainingEventNodes) {
			EventType eventType = EventType.get(eventNode.get("event_type").asInt());

			Event event;
			try {
				switch (eventType) {
				case MESSAGE_POSTED:
					event = EventParsers.messagePosted(eventNode);
					break;
				case MESSAGE_EDITED:
					event = EventParsers.messageEdited(eventNode);
					break;
				case USER_ENTERED:
					event = EventParsers.userEntered(eventNode);
					break;
				case USER_LEFT:
					event = EventParsers.userLeft(eventNode);
					break;
				case MESSAGE_STARRED:
					event = EventParsers.messageStarred(eventNode);
					break;
				case MESSAGE_DELETED:
					event = EventParsers.messageDeleted(eventNode);
					break;
				default:
					logger.warning("[room " + roomId + "]: Ignoring event with unknown \"event_type\":\n" + JsonUtils.prettyPrint(eventNode) + "\n");
					continue;
				}
			} catch (Exception e) {
				logger.log(Level.SEVERE, "[room " + roomId + "]: Ignoring event that could not be parsed:\n" + JsonUtils.prettyPrint(eventNode), e);
				continue;
			}

			eventsToPublish.add(event);
		}

		for (Event event : eventsToPublish) {
			List<Consumer<Event>> eventListeners = listeners.get(event.getClass());
			synchronized (eventListeners) {
				for (Consumer<Event> listener : eventListeners) {
					listener.accept(event);
				}
			}
		}
	}

	@SuppressWarnings("unchecked")
	public <T extends Event> void addEventListener(Class<T> clazz, Consumer<T> listener) {
		List<Consumer<Event>> eventListeners = listeners.get(clazz);
		synchronized (eventListeners) {
			eventListeners.add((Consumer<Event>) listener);
		}
	}

	/**
	 * Posts a message. If the message exceeds the max message size, it will be
	 * truncated.
	 * @param message the message to post
	 * @return the ID of the new message
	 * @throws RoomPermissionException if the user doesn't have permission to
	 * post messages to the room
	 * @throws IOException if there's a network problem
	 */
	public long sendMessage(String message) throws IOException {
		return sendMessage(message, SplitStrategy.NONE).get(0);
	}

	/**
	 * Posts a message.
	 * @param message the message to post
	 * @param splitStrategy defines how the message should be split up if the
	 * message exceeds the chat connection's max message size
	 * @return the ID(s) of the new message(s). This list will contain multiple
	 * IDs if the message was split up into multiple messages.
	 * @throws RoomPermissionException if the user doesn't have permission to
	 * post messages to the room
	 * @throws IOException if there's a network problem
	 */
	public List<Long> sendMessage(String message, SplitStrategy splitStrategy) throws IOException {
		if (!canPost) {
			throw new RoomPermissionException(roomId);
		}

		List<String> parts;
		if (message.contains("\n")) {
			//messages with newlines have no length limit
			parts = Arrays.asList(message);
		} else {
			parts = splitStrategy.split(message, MAX_MESSAGE_LENGTH);
		}

		List<Long> messageIds = new ArrayList<>(parts.size());
		for (String part : parts) {
			//@formatter:off
			Response response = http.post(chatDomain + "/chats/" + roomId + "/messages/new",
				"text", part,
				"fkey", fkey
			);
			//@formatter:on

			if (response.getStatusCode() == 404) {
				/*
				 * We already checked to make sure the room exists. So, if a 404
				 * response is returned when trying to send a message, it likely
				 * means that the bot's permission to post messages has been
				 * revoked.
				 * 
				 * If a 404 response is returned from this request, the response
				 * body reads:
				 * "The room does not exist, or you do not have permission"
				 */
				throw notFound(response, "post a message");
			}

			JsonNode body = response.getBodyAsJson();
			long id = body.get("id").asLong();
			messageIds.add(id);
		}

		return messageIds;
	}

	private IOException notFound(Response response, String action) {
		return new IOException("[roomId=" + roomId + "]: 404 response received when trying to " + action + ": " + response.getBody());
	}

	/**
	 * Gets the most recent messages from a room.
	 * @param count the number of messages to retrieve
	 * @return the messages
	 * @throws IOException if there's a network problem
	 */
	public List<ChatMessage> getMessages(int count) throws IOException {
		//@formatter:off
		Response response = http.post(chatDomain + "/chats/" + roomId + "/events",
			"mode", "messages",
			"msgCount", count,
			"fkey", fkey
		);
		//@formatter:on

		if (response.getStatusCode() == 404) {
			throw notFound(response, "get messages");
		}

		JsonNode body = response.getBodyAsJson();
		JsonNode events = body.get("events");

		List<ChatMessage> messages;
		if (events == null) {
			messages = new ArrayList<>(0);
		} else {
			messages = new ArrayList<>();
			Iterator<JsonNode> it = events.elements();
			while (it.hasNext()) {
				JsonNode element = it.next();
				ChatMessage chatMessage = EventParsers.extractChatMessage(element);
				messages.add(chatMessage);
			}
		}

		return messages;
	}

	/**
	 * Deletes a message. You can only delete your own messages. Messages older
	 * than two minutes cannot be deleted.
	 * @param messageId the ID of the message to delete
	 * @throws IOException if there's a problem deleting the message
	 */
	public void deleteMessage(long messageId) throws IOException {
		//@formatter:off
		Response response = http.post(chatDomain + "/messages/" + messageId + "/delete",
			"fkey", fkey
		);
		//@formatter:on

		int statusCode = response.getStatusCode();
		if (statusCode == 302) {
			throw new IOException("Message ID " + messageId + " was never assigned to a message.");
		}

		String body = response.getBody();
		switch (body) {
		case "\"ok\"":
		case "\"This message has already been deleted.\"":
			//message successfully deleted
			break;
		case "\"It is too late to delete this message\"":
			throw new IOException("Message " + messageId + " cannot be deleted because it is too old.");
		case "\"You can only delete your own messages\"":
			throw new IOException("Message " + messageId + " cannot be deleted because it was posted by somebody else.");
		default:
			logger.warning("Unexpected response when attempting to delete message [room=" + roomId + ", id=" + messageId + "]: " + body);
			break;
		}
	}

	/**
	 * Edits an existing message. You can only edit your own messages. Messages
	 * older than two minutes cannot be edited.
	 * @param messageId the ID of the message to edit
	 * @param updatedMessage the updated message
	 * @throws IOException if there's a problem editing the message
	 */
	public void editMessage(long messageId, String updatedMessage) throws IOException {
		//@formatter:off
		Response response = http.post(chatDomain + "/messages/" + messageId,
			"text", updatedMessage,
			"fkey", fkey
		);
		//@formatter:on

		int statusCode = response.getStatusCode();
		if (statusCode == 302) {
			throw new IOException("Message ID " + messageId + " was never assigned to a message.");
		}

		String body = response.getBody();
		switch (body) {
		case "\"ok\"":
			//message successfully edited
			break;
		case "\"This message has already been deleted and cannot be edited\"":
			throw new IOException("Message " + messageId + " cannot be edited because it was deleted.");
		case "\"It is too late to edit this message.\"":
			throw new IOException("Message " + messageId + " cannot be edited because it is too old.");
		case "\"You can only edit your own messages\"":
			throw new IOException("Message " + messageId + " cannot be edited because it was posted by somebody else.");
		default:
			logger.warning("Unexpected response when attempting to edit message [room=" + roomId + ", id=" + messageId + "]: " + body);
			break;
		}
	}

	/**
	 * Gets information about room users, such as their reputation and username.
	 * @param userIds the user ID(s)
	 * @return the user information
	 * @throws IOException if there's a network problem
	 */
	public List<UserInfo> getUserInfo(List<Integer> userIds) throws IOException {
		//@formatter:off
		Response response = http.post(chatDomain + "/user/info",
			"ids", StringUtils.join(userIds, ","),
			"roomId", roomId
		);
		//@formatter:on

		if (response.getStatusCode() == 404) {
			throw notFound(response, "getting user info");
		}

		JsonNode usersNode = response.getBodyAsJson().get("users");
		if (usersNode == null) {
			return null;
		}

		List<UserInfo> users = new ArrayList<>(usersNode.size());
		for (JsonNode userNode : usersNode) {
			UserInfo.Builder builder = new UserInfo.Builder();
			builder.userId(userNode.get("id").asInt());
			builder.roomId(roomId);
			builder.username(userNode.get("name").asText());

			String profilePicture;
			String emailHash = userNode.get("email_hash").asText();
			if (emailHash.startsWith("!")) {
				profilePicture = emailHash.substring(1);
			} else {
				profilePicture = "https://www.gravatar.com/avatar/" + emailHash + "?d=identicon&s=128";
			}
			builder.profilePicture(profilePicture);

			builder.reputation(userNode.get("reputation").asInt());
			builder.moderator(userNode.get("is_moderator").asBoolean());
			builder.owner(userNode.get("is_owner").asBoolean());
			builder.lastPost(timestamp(userNode.get("last_post").asLong()));
			builder.lastSeen(timestamp(userNode.get("last_seen").asLong()));

			users.add(builder.build());
		}
		return users;
	}

	/**
	 * Gets the users in a room that are "pingable". Pingable users receive
	 * notifications if they are mentioned. If a user is pingable, it does not
	 * necessarily mean they are currently in the room, although they could be.
	 * @return the pingable users
	 * @throws IOException if there's a network problem
	 */
	public List<PingableUser> getPingableUsers() throws IOException {
		Response response = http.get(chatDomain + "/rooms/pingable/" + roomId);

		if (response.getStatusCode() == 404) {
			throw notFound(response, "getting pingable users");
		}

		JsonNode root = response.getBodyAsJson();
		List<PingableUser> users = new ArrayList<>(root.size());
		for (JsonNode node : root) {
			if (!node.isArray() || node.size() < 4) {
				continue;
			}

			long userId = node.get(0).asLong();
			String username = node.get(1).asText();
			LocalDateTime lastPost = timestamp(node.get(3).asLong());

			users.add(new PingableUser(roomId, userId, username, lastPost));
		}
		return users;
	}

	/**
	 * Gets information about the room, such as its name and description.
	 * @return the room info
	 * @throws IOException if there's a network problem
	 */
	public RoomInfo getRoomInfo() throws IOException {
		Response response = http.get(chatDomain + "/rooms/thumbs/" + roomId);

		if (response.getStatusCode() == 404) {
			throw notFound(response, "getting room info");
		}

		JsonNode root = response.getBodyAsJson();

		int id = root.get("id").asInt();
		String name = root.get("name").asText();
		String description = root.get("description").asText();
		List<String> tags = Jsoup.parse(root.get("tags").asText()).getElementsByTag("a").stream().map(Element::html).collect(Collectors.toList());

		return new RoomInfo(id, name, description, tags);
	}

	/**
	 * Leaves the room and closes the web socket connection.
	 */
	public void leave() {
		chatClient.removeRoom(this);

		try {
			//@formatter:off
			http.post(chatDomain + "/chats/leave/" + roomId,
				"quiet", "true", //setting this parameter to "false" results in an error
				"fkey", fkey
			);
			//@formatter:on
		} catch (Exception e) {
			logger.log(Level.SEVERE, "[room=" + roomId + "]: Problem leaving room.", e);
		}

		try {
			close();
		} catch (IOException e) {
			logger.log(Level.SEVERE, "[room=" + roomId + "]: Problem closing websocket session.", e);
		}
	}

	@Override
	public void close() throws IOException {
		session.close();
	}

	private static class EventParsers {
		/**
		 * Parses a "message posted" event.
		 * @param element the JSON element to parse
		 * @return the parsed event
		 */
		public static MessagePostedEvent messagePosted(JsonNode element) {
			MessagePostedEvent.Builder builder = new MessagePostedEvent.Builder();

			extractEventFields(element, builder);
			builder.message(extractChatMessage(element));

			return builder.build();
		}

		/**
		 * Parses a "message edited" event.
		 * @param element the JSON element to parse
		 * @return the parsed event
		 */
		public static MessageEditedEvent messageEdited(JsonNode element) {
			MessageEditedEvent.Builder builder = new MessageEditedEvent.Builder();

			extractEventFields(element, builder);
			builder.message(extractChatMessage(element));

			return builder.build();
		}

		/**
		 * Parses a "message deleted" event.
		 * @param element the JSON element to parse
		 * @return the parsed event
		 */
		public static MessageDeletedEvent messageDeleted(JsonNode element) {
			MessageDeletedEvent.Builder builder = new MessageDeletedEvent.Builder();

			extractEventFields(element, builder);
			builder.message(extractChatMessage(element));

			return builder.build();
		}

		/**
		 * Parses a "message starred" event.
		 * @param element the JSON element to parse
		 * @return the parsed event
		 */
		public static MessageStarredEvent messageStarred(JsonNode element) {
			MessageStarredEvent.Builder builder = new MessageStarredEvent.Builder();

			extractEventFields(element, builder);
			builder.message(extractChatMessage(element));

			return builder.build();
		}

		/**
		 * Parses a "user entered" event.
		 * @param element the JSON element to parse
		 * @return the parsed event
		 */
		public static UserEnteredEvent userEntered(JsonNode element) {
			UserEnteredEvent.Builder builder = new UserEnteredEvent.Builder();

			extractEventFields(element, builder);

			JsonNode value = element.get("room_id");
			if (value != null) {
				builder.roomId(value.asInt());
			}

			value = element.get("room_name");
			if (value != null) {
				builder.roomName(value.asText());
			}

			value = element.get("user_id");
			if (value != null) {
				builder.userId(value.asInt());
			}

			value = element.get("user_name");
			if (value != null) {
				builder.username(value.asText());
			}

			return builder.build();
		}

		/**
		 * Parses a "user left" event.
		 * @param element the JSON element to parse
		 * @return the parsed event
		 */
		public static UserLeftEvent userLeft(JsonNode element) {
			UserLeftEvent.Builder builder = new UserLeftEvent.Builder();

			extractEventFields(element, builder);

			JsonNode value = element.get("room_id");
			if (value != null) {
				builder.roomId(value.asInt());
			}

			value = element.get("room_name");
			if (value != null) {
				builder.roomName(value.asText());
			}

			value = element.get("user_id");
			if (value != null) {
				builder.userId(value.asInt());
			}

			value = element.get("user_name");
			if (value != null) {
				builder.username(value.asText());
			}

			return builder.build();
		}

		/*
		 * When messages are moved out of a room, the chat system posts a new
		 * message under the name of the user who moved the messages. The
		 * content of this message contains the ID and name of the room that the
		 * messages were moved to. This regex parses that content.
		 */
		private static final Pattern messagesMovedOutRegex = Pattern.compile("^&rarr; <i><a href=\".*?\">\\d+ messages?</a> moved to <a href=\".*?/rooms/(\\d+)/.*?\">(.*?)</a></i>$");

		/**
		 * Parses a "messages moved out" event.
		 * @param eventsByType the complete list of events pushed to us by the
		 * web socket. This parameter must be mutable because this method will
		 * remove items from it to indicate that they shouldn't be processed by
		 * another event handler.
		 * @return the event to fire on our end or null if the given map does
		 * not contain any "message moved" events
		 */
		public static MessagesMovedEvent messagesMovedOut(Multimap<EventType, JsonNode> eventsByType) {
			Collection<JsonNode> moveEvents = eventsByType.removeAll(EventType.MESSAGE_MOVED_OUT);
			if (moveEvents.isEmpty()) {
				return null;
			}

			MessagesMovedEvent.Builder builder = new MessagesMovedEvent.Builder();

			List<ChatMessage> messages = new ArrayList<>(moveEvents.size());
			for (JsonNode event : moveEvents) {
				ChatMessage message = extractChatMessage(event);
				messages.add(message);
			}
			builder.messages(messages);

			/*
			 * When messages are moved, the chat system posts a new message
			 * under the name of the user who moved the messages. This causes a
			 * "new message" event to be posted. The content of this message
			 * contains the ID and name of the room that the messages were moved
			 * to.
			 */
			Collection<JsonNode> messagePostedEvents = eventsByType.get(EventType.MESSAGE_POSTED);
			JsonNode matchingEvent = null;
			for (JsonNode event : messagePostedEvents) {
				MessagePostedEvent messagePostedEvent = EventParsers.messagePosted(event);
				ChatMessage message = messagePostedEvent.getMessage();

				Matcher m = messagesMovedOutRegex.matcher(message.getContent());
				if (!m.find()) {
					continue;
				}

				//@formatter:off
				builder
				.destRoomId(Integer.parseInt(m.group(1)))
				.destRoomName(m.group(2))
				.sourceRoomId(message.getRoomId())
				.sourceRoomName(message.getRoomName())
				.moverUserId(message.getUserId())
				.moverUsername(message.getUsername())
				.eventId(messagePostedEvent.getEventId())
				.timestamp(messagePostedEvent.getTimestamp());
				//@formatter:on

				matchingEvent = event;

				break;
			}

			/*
			 * Remove the "new message" event so it is not processed again as a
			 * normal message. The event cannot be removed from within the
			 * foreach loop because a "concurrent modification" exception will
			 * be thrown.
			 */
			if (matchingEvent != null) {
				messagePostedEvents.remove(matchingEvent);
			}

			return builder.build();
		}

		/*
		 * When messages are moved into a room, the chat system posts a new
		 * message under the name of the user who moved the messages. The
		 * content of this message contains the ID and name of the room that the
		 * messages were moved from. This regex parses that content.
		 */
		private static final Pattern messagesMovedInRegex = Pattern.compile("^&larr; <i>\\d+ messages? moved from <a href=\".*?/rooms/(\\d+)/.*?\">(.*?)</a></i>$");

		/**
		 * Parses a "messages moved in" event.
		 * @param eventsByType the complete list of events pushed to us by the
		 * web socket. This parameter must be mutable because this method will
		 * remove items from it to indicate that they shouldn't be processed by
		 * another event handler.
		 * @return the event to fire on our end or null if the given map does
		 * not contain any "message moved" events
		 */
		public static MessagesMovedEvent messagesMovedIn(Multimap<EventType, JsonNode> eventsByType) {
			Collection<JsonNode> moveEvents = eventsByType.removeAll(EventType.MESSAGE_MOVED_IN);
			if (moveEvents.isEmpty()) {
				return null;
			}

			MessagesMovedEvent.Builder builder = new MessagesMovedEvent.Builder();

			List<ChatMessage> messages = new ArrayList<>(moveEvents.size());
			for (JsonNode event : moveEvents) {
				ChatMessage message = extractChatMessage(event);
				messages.add(message);
			}
			builder.messages(messages);

			/*
			 * When messages are moved, the chat system posts a new message
			 * under the name of the user who moved the messages. This causes a
			 * "new message" event to be posted. The content of this message
			 * contains the ID and name of the room that the messages were moved
			 * from.
			 */
			Collection<JsonNode> messagePostedEvents = eventsByType.get(EventType.MESSAGE_POSTED);
			JsonNode matchingEvent = null;
			for (JsonNode event : messagePostedEvents) {
				MessagePostedEvent messagePostedEvent = EventParsers.messagePosted(event);
				ChatMessage message = messagePostedEvent.getMessage();

				Matcher m = messagesMovedInRegex.matcher(message.getContent());
				if (!m.find()) {
					continue;
				}

				//@formatter:off
				builder
				.sourceRoomId(Integer.parseInt(m.group(1)))
				.sourceRoomName(m.group(2))
				.destRoomId(message.getRoomId())
				.destRoomName(message.getRoomName())
				.moverUserId(message.getUserId())
				.moverUsername(message.getUsername())
				.eventId(messagePostedEvent.getEventId())
				.timestamp(messagePostedEvent.getTimestamp());
				//@formatter:on

				matchingEvent = event;

				break;
			}

			/*
			 * Remove the "new message" event so it is not processed again as a
			 * normal message. The event cannot be removed from within the
			 * foreach loop because a "concurrent modification" exception will
			 * be thrown.
			 */
			if (matchingEvent != null) {
				messagePostedEvents.remove(matchingEvent);
			}

			return builder.build();
		}

		/**
		 * Parses any "reply" events that were pushed to us by the web socket.
		 * @param eventsByType the complete list of events pushed to us by the
		 * web socket. This parameter must be mutable because this method will
		 * remove items from it to indicate that they shouldn't be processed by
		 * another event handler.
		 * @return the events to fire on our end. This list will consist of
		 * {@link MessagePostedEvent} and {@link MessageEditedEvent} objects.
		 */
		public static List<Event> reply(Multimap<EventType, JsonNode> eventsByType) {
			List<Event> events = new ArrayList<>();

			Collection<JsonNode> newMessageEvents = eventsByType.get(EventType.MESSAGE_POSTED);
			Collection<JsonNode> editedMessageEvents = eventsByType.get(EventType.MESSAGE_EDITED);
			Collection<JsonNode> replyEvents = eventsByType.removeAll(EventType.REPLY_POSTED);

			for (JsonNode replyEvent : replyEvents) {
				ChatMessage message = extractChatMessage(replyEvent);

				JsonNode value = replyEvent.get("id");
				long eventId = (value == null) ? 0 : value.asLong();

				/*
				 * Whenever a "reply" event is posted, an accompanying
				 * "new message" or "message edited" event is also posted. This
				 * event has less information than the "reply" event, so ignore
				 * it. But we need to know which one was fired so we know what
				 * kind of event to fire on our end.
				 */

				JsonNode event = findMessageWithId(newMessageEvents, message.getMessageId());
				if (event != null) {
					newMessageEvents.remove(event);

					//@formatter:off
					events.add(new MessagePostedEvent.Builder()
						.message(message)
						.eventId(eventId)
						.timestamp(message.getTimestamp())
						.build()
					);
					//@formatter:on

					continue;
				}

				event = findMessageWithId(editedMessageEvents, message.getMessageId());
				if (event != null) {
					editedMessageEvents.remove(event);

					//@formatter:off
					events.add(new MessageEditedEvent.Builder()
						.message(message)
						.eventId(eventId)
						.timestamp(message.getTimestamp())
						.build()
					);
					//@formatter:on
					continue;
				}

				/*
				 * If an accompanying "new message" or "message edited" event is
				 * not found, it means that the "reply" event is from another
				 * room, so ignore it.
				 */
			}

			return events;
		}

		/**
		 * Parses any "user mentioned" events that were pushed to us by the web
		 * socket.
		 * @param eventsByType the complete list of events pushed to us by the
		 * web socket. This parameter must be mutable because this method will
		 * remove items from it to indicate that they shouldn't be processed by
		 * another event handler.
		 * @return the events to fire on our end. This list will consist of
		 * {@link MessagePostedEvent} and {@link MessageEditedEvent} objects.
		 */
		public static List<Event> mention(Multimap<EventType, JsonNode> eventsByType) {
			List<Event> events = new ArrayList<>();

			Collection<JsonNode> newMessageEvents = eventsByType.get(EventType.MESSAGE_POSTED);
			Collection<JsonNode> editedMessageEvents = eventsByType.get(EventType.MESSAGE_EDITED);
			Collection<JsonNode> mentionEvents = eventsByType.removeAll(EventType.USER_MENTIONED);

			for (JsonNode mentionEvent : mentionEvents) {
				ChatMessage message = extractChatMessage(mentionEvent);

				JsonNode value = mentionEvent.get("id");
				long eventId = (value == null) ? 0 : value.asLong();

				/*
				 * Whenever a "user mentioned" event is posted, an accompanying
				 * "new message" or "message edited" event is also posted. This
				 * event has less information than the "user mentioned" event,
				 * so ignore it. But we need to know which one was fired so we
				 * know what kind of event to fire on our end.
				 */
				JsonNode event = findMessageWithId(newMessageEvents, message.getMessageId());
				if (event != null) {
					newMessageEvents.remove(event);

					//@formatter:off
					events.add(new MessagePostedEvent.Builder()
						.eventId(eventId)
						.timestamp(message.getTimestamp())
						.message(message)
						.build()
					);
					//@formatter:on
				}

				event = findMessageWithId(editedMessageEvents, message.getMessageId());
				if (event != null) {
					editedMessageEvents.remove(event);

					//@formatter:off
					events.add(new MessageEditedEvent.Builder()
						.eventId(eventId)
						.timestamp(message.getTimestamp())
						.message(message)
						.build()
					);
					//@formatter:on
				}
			}

			return events;
		}

		private static JsonNode findMessageWithId(Collection<JsonNode> events, long id) {
			for (JsonNode event : events) {
				JsonNode value = event.get("message_id");
				if (value == null) {
					continue;
				}

				if (id == value.asLong()) {
					return event;
				}
			}
			return null;
		}

		private static void extractEventFields(JsonNode element, Event.Builder<?, ?> builder) {
			JsonNode value = element.get("id");
			if (value != null) {
				builder.eventId(value.asLong());
			}

			value = element.get("time_stamp");
			if (value != null) {
				builder.timestamp(timestamp(value.asLong()));
			}
		}

		/**
		 * Parses a {@link ChatMessage} object from the given JSON node.
		 * @param element the JSON node
		 * @return the parsed chat message
		 */
		public static ChatMessage extractChatMessage(JsonNode element) {
			ChatMessage.Builder builder = new ChatMessage.Builder();

			JsonNode value = element.get("message_id");
			if (value != null) {
				builder.messageId(value.asLong());
			}

			value = element.get("time_stamp");
			if (value != null) {
				LocalDateTime ts = timestamp(value.asLong());
				builder.timestamp(ts);
			}

			value = element.get("room_id");
			if (value != null) {
				builder.roomId(value.asInt());
			}

			value = element.get("room_name");
			if (value != null) {
				builder.roomName(value.asText());
			}

			/*
			 * This field is not present for "message starred" events".
			 */
			value = element.get("user_id");
			if (value != null) {
				builder.userId(value.asInt());
			}

			/*
			 * This field is not present for "message starred" events".
			 */
			value = element.get("user_name");
			if (value != null) {
				builder.username(value.asText());
			}

			/*
			 * This field is only present if the message has been edited.
			 */
			value = element.get("edits");
			if (value != null) {
				builder.edits(value.asInt());
			}

			/*
			 * This field is only present if the message has been starred.
			 */
			value = element.get("message_stars");
			if (value != null) {
				builder.stars(value.asInt());
			}

			/*
			 * This field is only present when the message is a reply to another
			 * message.
			 */
			value = element.get("parent_id");
			if (value != null) {
				builder.parentMessageId(value.asLong());
			}

			/*
			 * This field is only present if the message contains a valid
			 * mention or if the message is a reply to another message.
			 */
			value = element.get("target_user_id");
			if (value != null) {
				builder.mentionedUserId(value.asInt());
			}

			/*
			 * This field is not present for "message deleted" events.
			 */
			value = element.get("content");
			if (value != null) {
				String content = value.asText();
				String inner = extractFixedFontContent(content);
				boolean fixedFont = (inner != null);
				if (!fixedFont) {
					inner = extractMultiLineContent(content);
					if (inner == null) {
						inner = content;
					}
				}

				builder.content(inner, fixedFont);
			}

			return builder.build();
		}

		private static final Pattern fixedWidthRegex = Pattern.compile("^<pre class='(full|partial)'>(.*?)</pre>$", Pattern.DOTALL);

		/**
		 * Extracts the message content from a message that is formatted in
		 * fixed font. Fixed font messages are enclosed in a &lt;pre&gt; tag.
		 * @param content the complete chat message content
		 * @return the extracted message content or null if the message is not
		 * fixed-font
		 */
		private static String extractFixedFontContent(String content) {
			Matcher m = fixedWidthRegex.matcher(content);
			return m.find() ? m.group(2) : null;
		}

		private static final Pattern multiLineRegex = Pattern.compile("^<div class='(full|partial)'>(.*?)</div>$", Pattern.DOTALL);

		/**
		 * Extracts the message content from a multi-line message. Multi-line
		 * messages are enclosed in a &lt;div&gt; tag. Also converts &lt;br&gt;
		 * tags to newlines.
		 * @param content the complete chat message content
		 * @return the extracted message content or null if the message is not
		 * multi-line
		 */
		private static String extractMultiLineContent(String content) {
			Matcher m = multiLineRegex.matcher(content);
			return m.find() ? m.group(2).replace(" <br> ", "\n") : null;
		}
	}

	/**
	 * Provides a list of all the types of events that the web socket API will
	 * push.
	 * @author Michael Angstadt
	 */
	private enum EventType {
		//@formatter:off
		MESSAGE_POSTED(1),
		MESSAGE_EDITED(2),
		USER_ENTERED(3),
		USER_LEFT(4),
		MESSAGE_STARRED(6),
		USER_MENTIONED(8),
		MESSAGE_DELETED(10),
		REPLY_POSTED(18),
		MESSAGE_MOVED_OUT(19),
		MESSAGE_MOVED_IN(20);
		//@formatter:on

		/**
		 * The value of the "event_type" field in the JSON object that the web
		 * socket sends.
		 */
		private final int id;

		/**
		 * @param id the event type ID
		 */
		private EventType(int id) {
			this.id = id;
		}

		/**
		 * Gets an event type given its ID
		 * @param id the event type ID
		 * @return the event type or null if not found
		 */
		public static EventType get(int id) {
			for (EventType eventType : values()) {
				if (eventType.id == id) {
					return eventType;
				}
			}
			return null;
		}
	}

	/**
	 * Converts a timestamp to a {@link LocalDateTime} instance.
	 * @param ts the timestamp (seconds since epoch)
	 * @return the {@link LocalDateTime} instance
	 */
	private static LocalDateTime timestamp(long ts) {
		Instant instant = Instant.ofEpochSecond(ts);
		return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
	}
}
