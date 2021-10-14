import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Queue;
import java.util.stream.Collectors;

/**
 * Server class.
 */
public class Server {
    private static boolean running = true;
    private static ArrayList<ClientHandler> clients;
    private static int idCounter = 0;
    private static final int maxRecentMessages = 10;
    private static final Queue<String> recentMessages = new ArrayDeque<>(maxRecentMessages);

    public static String welcomeMessage = "Welcome to the Server. Use /help for a list of commands.";

    // main loop, accepts clients
    public static void main(String[] args) throws IOException {
        // listen for connections on port 8080
        ServerSocket serverSocket = new ServerSocket(8080);
        // store clients in here
        clients = new ArrayList<>();

        System.out.println("Server started on port 8080");

        // main loop
        while (running) {
            // wait until a connection is received
            Socket socket = serverSocket.accept();

            // Try to create a new client handler, which automatically creates a new thread
            try {
                ClientHandler newClient = new ClientHandler(socket, idCounter);
                clients.add(newClient);

            } catch (IOException ex) {
                System.err.println("Error connecting to client");
            }

            idCounter++;
        }
    }

    public static synchronized void saveMessage(String message) {
        // store message
        if (recentMessages.size() == maxRecentMessages) {
            recentMessages.poll();
        }
        recentMessages.add(message);
    }

    public static String[] getSavedMessages() {
        return recentMessages.toArray(new String[0]);
    }

    /**
     * Shut the server down.
     */
    public static void shutdown() {
        System.out.println("Server shutting down");
        closeAll();
        running = false;
        System.exit(0);
    }

    /**
     * Close all client connections
     */
    public static synchronized void closeAll() {
        for (var client : clients) {
            client.close("Server shutting down");
        }
    }

    /**
     * Removes client from pool and announces their exit.
     * Should only be called by clients when as they close themselves
     */
    public static synchronized void closeClient(ClientHandler client) {
        clients.remove(client);
        systemMessage(String.format("%s quit", client.name));
        System.out.println(client.name + " has left the chat!");
    }

    /**
     * Sends a message to every user.
     *
     * @param message The message to send, should be pre formatted
     */
    public static synchronized void sendToAll(String message) {
        for (ClientHandler client : clients) {
            if (!client.initialized) continue;
            try {
                client.sendMessage(message);
            } catch (IOException ex) {
                System.err.println(ex.toString());
            }
        }
    }

    /**
     * Send to everyone except the sender.
     *
     * @param sender  The client to avoid sending the message to.
     * @param message The message to send.
     */
    public static synchronized void sendToOthers(ClientHandler sender, String message) {
        for (ClientHandler client : clients) {
            if (client.initialized && !client.equals(sender)) {
                try {
                    client.sendMessage(message);
                } catch (IOException ex) {
                    System.err.printf("Error sending message to client %d:%s%n", client.id, client.name);
                    System.err.println(ex.toString());
                }
            }
        }
    }

    /**
     * Send a message to a client with a specific name
     *
     * @param sender        The sender, to send results back to them
     * @param recipientName The nickname of the person to send it to.
     * @param message       The message to send.
     */
    public static synchronized void sendByName(ClientHandler sender, String recipientName, String message) {
        // get all users that have this name
        boolean foundMatch = false;
        for (ClientHandler client : clients) {
            if (client.name.equals(recipientName)) {
                try {
                    client.sendMessage(message);
                    foundMatch = true;
                } catch (IOException e) {
                    client.close("Connection error");
                }
            }
        }

        try {
            if (foundMatch) {
                sender.sendMessage(message);
            } else {
                sender.sendMessage("Target not found");
            }
        } catch (IOException ex) {
            sender.close("Connection error");
        }
    }

    /**
     * Convenience method for making a system announcement
     *
     * @param message The message to announce.
     */
    public static synchronized void systemMessage(String message) {
        String formatted = String.format("{SYSTEM} %s", message);
        sendToAll(formatted);
        saveMessage(formatted);
    }

    public static String getUserNames() {
        return clients.stream().map(client -> client.name).collect(Collectors.joining(", "));
    }
}

/**
 * Client Handler.
 */
class ClientHandler implements Runnable {
    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;

    public final int id;
    public boolean running = true;
    public Thread thread;
    public String name = "guest";
    public boolean initialized = false;

    public ClientHandler(Socket socket, int id) throws IOException {
        this.socket = socket;
        this.id = id;

        try {
            in = new DataInputStream(socket.getInputStream());
            out = new DataOutputStream(socket.getOutputStream());

        } catch (IOException ex) {
            System.err.println("Error connecting to client");
            throw ex;
        }
        thread = new Thread(this, "Client " + id);
        thread.start();
    }

    @Override
    public void run() {
        try {
            name = in.readUTF();
            System.out.println(name + " has joined the chat!");
            sendMessage(Server.welcomeMessage);
            String recentMessages = String.join("\n", Server.getSavedMessages());
            // if we don't do this check, when there is no recent messages the client prints a newline
            if (recentMessages.length() > 0) {
                sendMessage(String.join("\n", Server.getSavedMessages()));
            }
            Server.systemMessage(String.format("[%s] joined the server", name));
            initialized = true;
            while (running) {
                String message = in.readUTF();
                handleMessage(message);
            }
        } catch (IOException ex) {
            // this is a SocketException if the problem is client-side
            close(null);
        }
    }

    /**
     * Close the connection
     *
     * @param reason Optional string reason.
     */
    public void close(String reason) {
        if (!running) return;
        try {
            if (reason != null && reason.length() > 0) {
                sendMessage("Closing: " + reason);
            } else {
                sendMessage("Closing");
            }
            if (socket.isClosed()) return;
            in.close();
            out.close();
            socket.close();
        } catch (IOException ignored) {
        }
        running = false;

        // remove from pool
        Server.closeClient(this);
    }

    public void sendMessage(String message) throws IOException {
        out.writeUTF(message);
    }

    void handleMessage(String message) throws IOException {
        // if it starts with /, it's a command
        if (message.startsWith("/")) {
            handleCommand(message);
            return;
        }
        // otherwise, it's just a message

        String formatted = String.format("%s: %s", this.name, message);
        Server.saveMessage(formatted);
        Server.sendToOthers(this, formatted);
    }

    void handleCommand(String message) throws IOException {
        String[] split = message.split(" ");
        switch (split[0]) {
            case "/help":
                String helpSelector = "";
                if (split.length > 1) helpSelector = split[1];
                handleHelp(helpSelector);
                return;
            case "/quit":
                close("Closing connection");
                return;
            case "/pm": {
                /// /pm {target} message...
                // check parameter count
                if (split.length < 2) {
                    sendMessage("Must provide target");
                    return;
                } else if (split.length < 3) {
                    sendMessage("Must provide message");
                    return;
                }

                // extract target
                String recipient = split[1];

                // check target length
                if (recipient.length() < 1) {
                    sendMessage("Invalid recipient");
                    return;
                }

                if (recipient.equals(name)) {
                    sendMessage("Can't send message to self!");
                    return;
                }

                // extract rest of message
                // get the location of the space after the target parameter
                int secondSpace = message.indexOf(' ', 4);
                // get everything after that space
                String content = message.substring(secondSpace + 1);

                // format message
                String messageToSend = String.format("%s -> %s: %s", name, recipient, content);

                // send
                Server.sendByName(this, recipient, messageToSend);
                return;
            }
            case "/me": {
                /// /me message...

                // if user coolperson sends "/me is cool"
                // sends to everyone "coolperson is cool"

                // get everything after "/me "
                String content = message.substring(4);
                String formatted = String.format("%s %s", name, content);

                // send
                Server.sendToAll(formatted);
                return;
            }
            case "/nick":
                String newName = split[1];
                String announcment = String.format("%s is changing their name to %s", name, newName);
                name = newName;
                Server.systemMessage(announcment);
                return;
            case "/welcome":
                // no parameters
                if (message.length() > 9) {
                    Server.welcomeMessage = message.substring(10);
                }
                sendMessage(Server.welcomeMessage);
                return;
            case "/userlist":
                String names = Server.getUserNames();
                sendMessage("Online users:");
                sendMessage(names);
                return;
            case "/closeServer":
                Server.shutdown();
                return;
        }
        sendMessage("Invalid or unknown command");
    }

    void handleHelp(String selector) throws IOException {
        if (selector.length() == 0) {
            // print out all commands
            String message = "Type /help command to learn more about a command\n" +
                    "Command list:\n" +
                    "/help, /quit, /pm, /me, /nick, /welcome, /userlist, /closeServer";
            sendMessage(message);
            return;
        }

        String helpMessage;
        switch (selector) {
            case "quit":
            case "/quit":
                helpMessage = "/quit: disconnects you from the server";
                break;
            case "help":
            case "/help":
                helpMessage = "/help: Displays help messages like this one :)";
                break;
            case "pm":
            case "/pm":
                helpMessage = "/pm {recipient} message...: Sends message to recipient";
                break;
            case "me":
            case "/me":
                helpMessage = "/me message...: Displays message after your name, for messages like \"Example is hungry\" from \"/me is hungry\"";
                break;
            case "nick":
            case "/nick":
                helpMessage = "/nick newNick: Changes your nickname";
                break;
            case "welcome":
            case "/welcome":
                helpMessage = "/welcome: Displays server welcome message\n" +
                        "/welcome message...: Sets the server welcome message";
                break;
            case "userlist":
            case "/userlist":
                helpMessage = "/userlist: Displays a list of the online users";
                break;
            case "closeServer":
            case "/closeServer":
                helpMessage = "/closeServer: Shuts down the server";
                break;
            default:
                helpMessage = "Invalid specifier, no help article found.";
        }
        sendMessage(helpMessage);
    }
}
