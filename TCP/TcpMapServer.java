import java.io.*;
import java.net.*;
import java.util.HashMap;
import java.util.Map;

/*
 * Name: Xintong Wang
 * Date: 09/19/2014
 * CSE 473, Lab2
 *
 * This module implements a simple TcpMapServer that stores (key,value)
 * strings.
 *
 * usage: MapServer [ portNumber ]
 *
 * There is a single optional command line argument that is the
 * number of the port that the server listens on. The port number
 * defaults to 30123.
 *
 * The server expects to receive TCP packets that store and retrieve
 * (key,value) pairs, where both the key and the value are strings.
 *
 * There are four commands: get, put, remove and get all. Get takes the key
 * as an argument and sends the value back to the client. Put takes
 * two arguments (the key and the value). If the key is already in
 * the server, it updates its mapped value with the value from the
 * function's arguments. If the key is not in the system, the key value
 * pair will be entered into it and the mapping is established. Remove
 * takes the key as an argument and removes that (key,value) pair from the
 * map server. Get all command returns all the key-value pair within the
 * map
 *
 * The packets contain contain ASCII strings separated by ":" characters.
 * For example, to perform the operation put("foo","bar"), the remote
 * client would send a packet containing the characters
 *
 * put:foo:bar
 *
 * To perform the operation get("who hah") the client would send a packet
 * containing
 *
 * get:who hah
 *
 * If the put is adding a new (key, value) pair to the map server, the
 * reply to a successful put is a packet containing the characters
 *
 * ok
 *
 * If the put is updating the value for a previously entered key,
 * the reply to a successful update is
 *
 * updated:key
 *
 * where key is the key of the updated (key,value) pair.
 * The reply to a succesful get is
 *
 * ok:result string
 *
 * where "result string" is the stored value.
 * If the key is not in the map server for a get or remove,
 * the packet should contain the response
 *
 * no match
 *
 * get all command returns all key value pair in the format
 *
 * key1:value1::key2:value2::key3:value3
 *
 * (comments based on solution from Lab1)
 */
public class TcpMapServer {
    private static final int DEFAULT_PORT = 30123;
    private static final String GET_OPERATION = "get";
    private static final String GET_ALL_OPERATION = "get all";
    private static final String PUT_OPERATION = "put";
    private static final String REMOVE_OPERATION = "remove";
    private static final String OK_MSG = "ok:";
    private static final String OK_MSG_END = "ok";
    private static final String UPDATE_MSG = "updated:";
    private static final String NO_MATCH_MSG = "no match";
    private static final String ERROR_MSG = "error:unrecognizable input:";
    private static final String COLON = ":";

    public static void main(String[] args) throws Exception {
        Map<String, String> myMap = new HashMap<String, String>();
        //initiate the server socket
        InetAddress myBindAddr  =
                args.length > 0 ? InetAddress.getByName(args[0]) : null;
        int myPort =
                args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_PORT;
        //create and bind listening socket
        ServerSocket myListenSocket = new ServerSocket(myPort, 0, myBindAddr);
        //wait for client connecting to the server
        while (true)
        {
            // wait for incoming connection request and
            // create new socket to handle it
            Socket connSock = myListenSocket.accept();
            // create buffered versions of socket's in/out streams
            BufferedReader in = new BufferedReader(new InputStreamReader(
                    connSock.getInputStream(), "US-ASCII"));
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(
                    connSock.getOutputStream(), "US-ASCII"));
            while (true) {
                String myInData = in.readLine();

                if (myInData == null || myInData.length() == 0) break;
                String myOutData = analyzeInput(myMap, myInData);

                out.write(myOutData);
                out.newLine();
                out.flush();
            }
            connSock.close();
        }
    }

    //helper method to analyze and execute the command received from the client
    private static String analyzeInput(Map<String, String> aMap, String aData){
        String[] myPayload = aData.split(COLON);
        String myOutData;
        String myCmd = myPayload[0];
        //four operations
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
        else if (myCmd.equals(GET_ALL_OPERATION))
        {
            if (myPayload.length == 1)
            {
                StringBuilder mySb = new StringBuilder("");
                for (String s: aMap.keySet())
                {
                    mySb.append(s).append(COLON).append(aMap.get(s)).append(COLON).append(COLON);
                }
                mySb.deleteCharAt(mySb.length() - 1);
                mySb.deleteCharAt(mySb.length() - 1);
                myOutData = mySb.toString();
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
        return myOutData;
    }
}

