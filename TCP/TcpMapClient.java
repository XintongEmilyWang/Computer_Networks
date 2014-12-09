import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.Socket;
/*
 * Name: Xintong Wang
 * Date: 09/19/2014
 * CSE 473, Lab2
 *
 * Simple client for use with a TcpMapServer that stores (key,value) strings.
 *
 * usage: TcpMapClient serverName serverPort cmdName [ arg1 arg2 ]
 *
 * Send a command to a remote MapServer in a TCP packet.
 * Wait for reply packet and print its contents.
 *
 * The first argument is the domain of a remote host,
 * the second is a port number used by a MapServer on that remote host.
 *
 * The cmdName is "get", "put", "remove", "get all" and the optional arguments
 * can be arbitrary strings.
 *
 * The client does no checking of the last three arguments,
 * allowing malformed packets to be sent to the server. This
 * allows us to verify that the server can detect malformed packets.
 */

public class TcpMapClient {
    private static final int DEFAULT_PORT = 30123;
    public static void main(String[] args) throws Exception{
        //create the client socket
        InetAddress myAddress = InetAddress.getByName(args[0]);
        int myPort = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_PORT;
        Socket myClientSocket = new Socket(myAddress, myPort);
        // create buffered reader & writer for socket's in/out streams
        BufferedReader myIn = new BufferedReader(new InputStreamReader(
                myClientSocket.getInputStream(),"US-ASCII"));
        BufferedWriter myOut = new BufferedWriter(new OutputStreamWriter(
                myClientSocket.getOutputStream(),"US-ASCII"));
        // create buffered reader for System.in
        BufferedReader mySysIn = new BufferedReader(new InputStreamReader(
                System.in));
        String line;
        while (true)
        {
            line = mySysIn.readLine();
            if (line == null || line.length() == 0) break;

            // write line on socket and print reply to System.out
            myOut.write(line);
            myOut.newLine();
            myOut.flush();
            //read msg from server
            System.out.println(myIn.readLine());
        }
        myClientSocket.close();
    }
}

