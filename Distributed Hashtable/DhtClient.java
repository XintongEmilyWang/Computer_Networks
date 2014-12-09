import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;


/**
 * SimpleDhtClient
 * send UDP packet to the DhtServer and wait for the server's reply
 *
 * argument format: SimpleDhtClient ip serverconfig cmd [key] [value]
 * the first is the IP address of the interface that the client shoud
 * bind to its own datagram socket
 * the second is the name of a config file containing the IP and port
 * number used by DhtServer
 * the third is the cmd operation
 * the forth is optional key
 * the fifth is optional value
 *
 * the Client will send and receive packet and set debug to true to
 * see every packet
 */
public class DhtClient {
    public static void main(String[] args) throws Exception{
        if (args.length < 3)
        {
            throw new Exception("Wrong Argument Length. Should be: " +
                    "SimpleDhtClient ip serverconfig cmd [key] [value].");
        }

        //create the client socket
        InetAddress myAddress = InetAddress.getByName(args[0]);
        DatagramSocket myClientSocket = new DatagramSocket(0, myAddress);

        BufferedReader myConfig = new BufferedReader(new InputStreamReader(
                new FileInputStream(args[1]), "US-ASCII"));
        String[] myServerInfo = myConfig.readLine().split(" ");
        InetSocketAddress myServerAddress =
                new InetSocketAddress(myServerInfo[0], Integer.parseInt(myServerInfo[1]));

        //sending packet
        String myCmd = args[2];
        String myKey = args.length > 3 ? args[3] : null;
        String myVal = args.length > 4 ? args[4] : null;

        Packet myPacket = new Packet();
        myPacket.type = myCmd;
        myPacket.key = myKey;
        myPacket.val = myVal;
        myPacket.tag = 12345;

        myPacket.send(myClientSocket, myServerAddress, true);
        Packet myReceivedPacket = new Packet();
        myReceivedPacket.receive(myClientSocket,true);
    }
}
