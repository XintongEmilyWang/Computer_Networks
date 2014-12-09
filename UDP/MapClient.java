import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/*
 * Name: Xintong Wang
 * Date: 09/07/2014
 * CSE 473, Lab1
 *
 * The client application sends command to the server with
 * 3 operations: get, put and remove. The client will send UDP
 * packet with a payload of ASCII characters.
 *
 */
public class MapClient {
    private static final int BUFFER_LENGTH = 1024;
    private static final String COLON = ":";
    public static void main(String[] args) throws Exception{
    	String myData;
        //analyze the command line input
    	if (args.length == 3)
    	{
    		myData = args[2];
    	}
    	else if (args.length == 4)
    	{
    		myData = args[2] + COLON + args[3];
    	}
    	else if (args.length == 5)
    	{
    		myData = args[2] + COLON + args[3] + COLON + args[4];
    	}
    	else{
    		throw new Exception("Wrong Argument Length.");
    	}

        //create the client socket
        DatagramSocket myClientSocket = new DatagramSocket();
        InetAddress myAddress = InetAddress.getByName(args[0]);
        int myPort = Integer.parseInt(args[1]);

        byte[] mySendBuf;
        byte[] myReceiveBuf = new byte[BUFFER_LENGTH];

        //send packet with IP address and port number specified
        mySendBuf = myData.getBytes();
        DatagramPacket mySendPkt = new DatagramPacket(mySendBuf,
                                                mySendBuf.length,
                                                        myAddress,
                                                           myPort);
        myClientSocket.send(mySendPkt);

        //receive the responding packet from the server
        DatagramPacket myReceivePkt =
                new DatagramPacket(myReceiveBuf, myReceiveBuf.length);
        myClientSocket.receive(myReceivePkt);
        String myServerResp = new String(myReceivePkt.getData());
        myServerResp = myServerResp.trim();
        System.out.println("From Server: " + myServerResp);

        //close the socket after receiving the packet
        myClientSocket.close();
    }
}
