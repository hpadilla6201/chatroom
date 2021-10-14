import java.io.*;
import java.net.Socket;
import java.util.Scanner;


/**
 * Client class.
 */
public class Client implements Runnable {

    private static Socket socket;
    private static DataInputStream in;
    private static DataOutputStream out;

    public Client() {
        //start thread
        new Thread(this).start();
    }

    // receive messages from server
    // print them out to command line
    // while true , use in variable
    @Override
    public void run() {
        try {
            // noinspection InfiniteLoopStatement
            while (true) {
                String message = in.readUTF();
                System.out.println(message);
            }
        } catch (EOFException ignored) {
            System.out.println("Connection closed by Server");
        } catch (IOException ex) {
            System.out.println("Connection error");
        }
        System.exit(0);
    }


    public static void main(String[] args) throws IOException {
        Socket socket = new Socket("localhost", 8080);

        in = new DataInputStream(socket.getInputStream());
        out = new DataOutputStream(socket.getOutputStream());

        Client client = new Client();

        // Ask user for name
        Scanner input = new Scanner(System.in);
        System.out.println("Enter User Name: ");
        String userName = "";
        boolean validName = false;
        while (!validName) {
            userName = input.nextLine();
            if (userName.contains(" ")) {
                System.out.println("Can't have spaces in name, try again:");
            } else {
                validName = true;
            }
        }

        System.out.println("Hello " + userName + "!");

        out.writeUTF(userName);
        // receive and send to server
        String message;
        while (input.hasNextLine()) {
            message = input.nextLine();
            out.writeUTF(message);
        }
    }
}
