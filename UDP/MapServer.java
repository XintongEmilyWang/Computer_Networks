import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;

/*
 * Name: Xintong Wang
 * Date: 09/07/2014
 * CSE 473, Lab1
 *
 * The server application implements a simple storage device with
 * 3 operations: get, put and remove. The server will accept UDP
 * packet with a payload of ASCII characters.
 *
 */

public class MapServer {
    private static final int DEFAULT_PORT = 30123;
    private static final int BUFFER_LENGTH = 1024;
    private static final String GET_OPERATION = "get";
    private static final String PUT_OPERATION = "put";
    private static final String REMOVE_OPERATION = "remove";
    private static final String OK_MSG = "ok:";
    private static final String OK_MSG_END = "ok";
    private static final String UPDATE_MSG = "updated:";
    private static final String NO_MATCH_MSG = "no match";
    private static final String ERROR_MSG = "error:unrecognizable input:";

    public static void main(String[] args) throws Exception {
        Map<String, String> myMap = new HashMap<String, String>();
        //initiate the server socket
        InetAddress myAddr = null;
        int myPort =
                args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        DatagramSocket myServerSocket = new DatagramSocket(myPort, myAddr);

        //wait for client connecting to the server
        while (true)
        {
        	byte[] myInBuf = new byte[BUFFER_LENGTH];
        	byte[] myOutBuf;
            //receive data packet from client
        	DatagramPacket myInPkt =
                    new DatagramPacket(myInBuf, myInBuf.length);
        	myServerSocket.receive(myInPkt);
        	String myInData = new String(myInPkt.getData());
        	myInData = myInData.trim();
        	System.out.println("From Client: " + myInData);
        	String mySendData = analyzeInput(myMap, myInData);

            //send responding packet to client
        	myOutBuf = mySendData.getBytes();
        	DatagramPacket myOutPkt = new DatagramPacket(myOutBuf,
                                                        myOutBuf.length,
                                                        myInPkt.getAddress(),
                                                        myInPkt.getPort());
        	myServerSocket.send(myOutPkt);
        }
    }

    //helper method to analyze and execute the command received from the client
    private static String analyzeInput(Map<String, String> aMap, String aData){
        String[] myPayload = aData.split(":");
        String myOutData;
        String myCmd = myPayload[0];

        //three operations
        if (myCmd.equals(GET_OPERATION))
        {
            if (myPayload.length == 2)
            {
                if (aMap.get(myPayload[1]) != null)
                {
                   myOutData = OK_MSG + aMap.get(myPayload[1]);
                }
                else
                {
                    myOutData = NO_MATCH_MSG;
                }
            }
            else
            {
                myOutData = ERROR_MSG + aData;
            }
        }
        else if (myCmd.equals(PUT_OPERATION))
        {
            if (myPayload.length == 3)
            {
                if (aMap.containsKey(myPayload[1]))
                {
                    aMap.put(myPayload[1], myPayload[2]);
                    myOutData = UPDATE_MSG + myPayload[1];
                }
                else
                {
                    aMap.put(myPayload[1], myPayload[2]);
                    myOutData = OK_MSG_END;
                }

            }
            else
            {
                myOutData = ERROR_MSG + aData;
            }
        }
        else if (myCmd.equals(REMOVE_OPERATION))
        {
            if (myPayload.length == 2)
            {
                if (aMap.remove(myPayload[1]) != null)
                {
                    myOutData = OK_MSG_END;
                }
                else
                {
                    myOutData = NO_MATCH_MSG;
                }
            }
            else
            {
                myOutData = ERROR_MSG + aData;
            }
        }
        else
        {
            myOutData = ERROR_MSG + aData;
        }
        System.out.println(aMap.toString());
        return myOutData;
    }
}
