import java.io.*;
import java.net.*;
import java.util.*;
import java.util.Map.Entry;
import java.lang.*;

import sun.misc.Signal;
import sun.misc.SignalHandler;
/** Server for simple distributed hash table that stores (key,value) strings.
 *  
 *  usage: DhtServer myIp numRoutes cfgFile [ cache ] [ debug ] [ predFile ]
 *  
 *  myIp	is the IP address to use for this server's socket
 *  numRoutes	is the max number of nodes allowed in the DHT's routing table;
 *  		typically lg(numNodes)
 *  cfgFile	is the name of a file in which the server writes the IP
 *		address and port number of its socket
 *  cache	is an optional argument; if present it is the literal string
 *		"cache"; when cache is present, the caching feature of the
 *		server is enabled; otherwise it is not
 *  debug	is an optional argument; if present it is the literal string
 *		"debug"; when debug is present, a copy of every packet received
 *		and sent is printed on stdout
 *  predFile	is an optional argument specifying the configuration file of
 *		this node's predecessor in the DHT; this file is used to obtain
 *		the IP address and port number of the precessor's socket,
 *		allowing this node to join the DHT by contacting predecessor
 *  
 *  The DHT uses UDP packets containing ASCII text. Here's an example of the
 *  UDP payload for a get request from a client.
 *  
 *  CSE473 DHTPv0.1
 *  type:get
 *  key:dungeons
 *  tag:12345
 *  ttl:100
 *  
 *  The first line is just an identifying string that is required in every
 *  DHT packet. The remaining lines all start with a keyword and :, usually
 *  followed by some additional text. Here, the type field specifies that
 *  this is a get request; the key field specifies the key to be looked up;
 *  the tag is a client-specified tag that is returned in the response; and
 *  can be used by the client to match responses with requests; the ttl is
 *  decremented by every DhtServer and if <0, causes the packet to be discarded.
 *  
 *  Possible responses to the above request include:
 *  
 *  CSE473 DHTPv0.1
 *  type:success
 *  key:dungeons
 *  value:dragons
 *  tag:12345
 *  ttl:95
 *  
 *  or
 *  
 *  CSE473 DHTPv0.1
 *  type:no match
 *  key:dungeons
 *  tag:12345
 *  ttl:95
 *  
 *  Put requests are formatted similarly, but in this case the client typically
 *  specifies a value field (omitting the value field causes the pair with the
 *  specified key to be removed).
 *  
 *  The packet type 'failure' is used to indicate an error of some sort; in 
 *  this case, the 'reason' field provides an explanation of the failure. 
 *  The 'join' type is used by a server to join an existing DHT. In the same
 *  way, the 'leave' type is used by the leaving server to circle around the 
 *  DHT asking other servers’ to delete it from their routing tables.  The 
 *  'transfer' type is used to transfer (key,value) pairs to a newly added 
 *  server. The 'update' type is used to update the predecessor, successor, 
 *  or hash range of another DHT server, usually when a join or leave even 
 *  happens. 
 *
 *  Other fields and their use are described briefly below
 *  clientAdr 	is used to specify the IP address and port number of the 
 *              client that sent a particular request; it is added to a request
 *              packet by the first server to receive the request, before 
 *              forwarding the packet to another node in the DHT; an example of
 *              the format is clientAdr:123.45.67.89:51349.
 *  relayAdr  	is used to specify the IP address and port number of the first
 *              server to receive a request packet from the client; it is added
 *              to the packet by the first server before forwarding the packet.
 *  hashRange 	is a pair of integers separated by a colon, specifying a range
 *              of hash indices; it is included in the response to a 'join' 
 *              packet, to inform the new DHT server of the set of hash values
 *              it is responsible for; it is also included in the update packet
 *              to update the hash range a server is responsible for.
 *  succInfo  	is the IP address and port number of a server, followed by its
 *              first hash index; this information is included in the response
 *              to a join packet to inform the new DHT server about its 
 *              immediate successor; it’s also included in the update packet 
 *              to change the immediate successor of a DHT server; an example 
 *              of the format is succInfo:123.45.6.7:5678:987654321.
 *  predInfo	is also the IP address and port number of a server, followed
 *              by its first hash index; this information is included in a join
 *              packet to inform the successor DHT server of its new 
 *              predecessor; it is also included in update packets to update 
 *              the new predecessor of a server.
 *  senderInfo	is the IP address and port number of a DHT server, followed by
 *              its first hash index; this information is sent by a DHT to 
 *              provide routing information that can be used by other servers.
 *              It also used in leave packet to let other servers know the IP
 *              address and port number information of the leaving server.
 */


public class DhtServer {
	private static int numRoutes;	// number of routes in routing table
	private static boolean cacheOn;	// enables caching when true
	private static boolean debug;	// enables debug messages when true

	private static HashMap<String,String> map;	// key/value pairs
	private static HashMap<String,String> cache;	// cached pairs
	private static List<Pair<InetSocketAddress,Integer>> rteTbl;

	private static DatagramSocket sock;
	private static InetSocketAddress myAdr;
	private static InetSocketAddress predecessor; // DHT predecessor
	private static Pair<InetSocketAddress,Integer> myInfo; 
	private static Pair<InetSocketAddress,Integer> predInfo; 
	private static Pair<InetSocketAddress,Integer> succInfo; // successor
	private static Pair<Integer,Integer> hashRange; // my DHT hash range
	private static int sendTag;		// tag for new outgoing packets
	// flag for waiting leave message circle back
	private static boolean stopFlag;
	 
	/** Main method for DHT server.
     *
     *
	 *  Processes command line arguments, initializes data, joins DHT,
	 *  then starts processing requests from clients.
	 */
	public static void main(String[] args) {
		// process command-line arguments
		if (args.length < 3) {
			System.err.println("usage: DhtServer myIp numRoutes " +
					   "cfgFile [debug] [ predFile ] ");
			System.exit(1);
		}
		numRoutes = Integer.parseInt(args[1]);
		String cfgFile = args[2];
		cacheOn = debug = false;
		stopFlag = false;
		String predFile = null;
		for (int i = 3; i < args.length; i++) {
			if (args[i].equals("cache")) cacheOn = true;
			else if (args[i].equals("debug")) debug = true;
			else predFile = args[i];
		}
		
		// open socket for receiving packets
		// write ip and port to config file
		// read predecessor's ip/port from predFile (if there is one)
		InetAddress myIp = null; sock = null; predecessor = null;
		try {	
			myIp = InetAddress.getByName(args[0]);
			sock = new DatagramSocket(0,myIp);
			BufferedWriter cfg =
				new BufferedWriter(
				    new OutputStreamWriter(
					new FileOutputStream(cfgFile),
					"US-ASCII"));
			cfg.write("" +	myIp.getHostAddress() + " " +
					sock.getLocalPort());
			cfg.newLine();
			cfg.close();
			if (predFile != null) {
				BufferedReader pred =
					new BufferedReader(
					    new InputStreamReader(
						new FileInputStream(predFile),
						"US-ASCII"));
				String s = pred.readLine();
				String[] chunks = s.split(" ");
				predecessor = new InetSocketAddress(
					chunks[0],Integer.parseInt(chunks[1]));
			}
		} catch(Exception e) {
			System.err.println("usage: DhtServer myIp numRoutes " +
					   "cfgFile [ cache ] [ debug ] " +
					   "[ predFile ] ");
			System.exit(1);
		}
		myAdr = new InetSocketAddress(myIp,sock.getLocalPort());
		
		// initialize data structures	
		map = new HashMap<String,String>();
		cache = new HashMap<String,String>();
		rteTbl = new LinkedList<Pair<InetSocketAddress,Integer>>();

		// join the DHT (if not the first node)
		hashRange = new Pair<Integer,Integer>(0,Integer.MAX_VALUE);
		myInfo = null;
		succInfo = null;
		predInfo = null;
		if (predecessor != null) {
			join(predecessor);
		} else {
			myInfo = new Pair<InetSocketAddress,Integer>(myAdr,0);
			succInfo = new Pair<InetSocketAddress,Integer>(myAdr,0);
			predInfo = new Pair<InetSocketAddress,Integer>(myAdr,0);
		}


		// start processing requests from clients
		Packet p = new Packet();
		Packet reply = new Packet();
		InetSocketAddress sender = null;
		sendTag = 1;

		/* this function will be called if there's a "TERM" or "INT"
		 * captured by the signal handler. It simply execute the leave
		 * function and leave the program.
		 */ 
		SignalHandler handler = new SignalHandler() {  
		    public void handle(Signal signal) {  
		        leave();
		        System.exit(0);
		    }  
		};
		//Signal.handle(new Signal("KILL"), handler); // capture kill -9 signal
		Signal.handle(new Signal("TERM"), handler); // capture kill -15 signal
		Signal.handle(new Signal("INT"), handler); // capture ctrl+c
		
		while (true) {
			try { sender = p.receive(sock,debug);
			} catch(Exception e) {
				System.err.println("received packet failure");
				continue;
			}
			if (sender == null) {
				System.err.println("received packet failure");
				continue;
			}
			if (!p.check()) {
				reply.clear();
				reply.type = "failure";
				reply.reason = p.reason;
				reply.tag = p.tag;
				reply.ttl = p.ttl;
				reply.send(sock,sender,debug);
				continue;
			}
			handlePacket(p,sender);
		}
	}

	/** Hash a string, returning a 32 bit integer.
	 *  @param s is a string, typically the key from some get/put operation.
	 *  @return and integer hash value in the interval [0,2^31).
	 */
	public static int hashit(String s) {
		while (s.length() < 16) s += s;
		byte[] sbytes = null;
		try { sbytes = s.getBytes("US-ASCII"); 
		} catch(Exception e) {
			System.out.println("illegal key string");
			System.exit(1);
		}
		int i = 0;
		int h = 0x37ace45d;
		while (i+1 < sbytes.length) {
			int x = (sbytes[i] << 8) | sbytes[i+1];
			h *= x;
			int top = h & 0xffff0000;
			int bot = h & 0xffff;
			h = top | (bot ^ ((top >> 16)&0xffff));
			i += 2;
		}
		if (h < 0) h = -(h+1);
		return h;
	}

	/** Leave an existing DHT.
	 *  
	 *	Send a leave packet to it's successor and wait until stopFlag is 
	 * 	set to "true", which means leave packet is circle back.
	 *
	 *	Send an update packet with the new hashRange and succInfo fields to 
	 *  its predecessor, and sends an update packet with the predInfo 
	 *  field to its successor. 
	 *	
	 *	Transfers all keys and values to predecessor.  
	 *	Clear all the existing cache, map and rteTbl information
	 */
	public static void leave() {
		// your code here
		//send a leave packet to its successor and let it travel around.
		Packet leavePkt = new Packet();
		leavePkt.type="leave";
		leavePkt.tag=sendTag++;
		leavePkt.senderInfo = new 
			    Pair<InetSocketAddress,Integer>(myAdr,hashRange.left);
		leavePkt.send(sock, succInfo.left, debug);
		
		//wait for the leave msg to go around.
		while(!stopFlag){};
		//update the predecessor's succInfo and hashRange.
		Packet updMsgToPred = new Packet();
		updMsgToPred.succInfo = succInfo;
		updMsgToPred.hashRange=new Pair<Integer,Integer>(predInfo.right,hashRange.right);
		updMsgToPred.tag=sendTag++;
		updMsgToPred.type="update";
		updMsgToPred.send(sock, predInfo.left, debug);
		
		//set the successor's predecessor to its predecessor.
		Packet updMsgToSucc = new Packet();
		updMsgToSucc.predInfo=predInfo;
		updMsgToSucc.type ="update";
		updMsgToSucc.tag=sendTag++;
		updMsgToSucc.send(sock, succInfo.left, debug);
		
		//transfer all the key value pairs to the predecessor.
		Iterator myIterator = map.entrySet().iterator();
		while(myIterator.hasNext()){
			Entry current = (Entry) myIterator.next();
			String key =(String) current.getKey();
			Packet transferPkt = new Packet();
			transferPkt.type="transfer";
			transferPkt.tag=sendTag++;
			transferPkt.key= key;
			transferPkt.val= (String) current.getValue();
			transferPkt.send(sock,predInfo.left , debug);
			
			//remove the the transfered key value pairs from the server.
			map.remove(key);
		}

		//clear all the data.
		map.clear();
		cache.clear();
		rteTbl.clear();
		
	}
	
	/** Handle a update packet from a prospective DHT node.
	 *  @param p is the received join packet
	 *  @param adr is the socket address of the host that
	 *  
	 *	The update message might contains infomation need update,
	 *	including predInfo, succInfo, and hashRange. 
	 *  And add the new Predecessor/Successor into the routing table.
	 *	If succInfo is updated, succInfo should be removed from 
	 *	the routing table and the new succInfo should be added
	 *	into the new routing table.
	 */
	public static void handleUpdate(Packet p, InetSocketAddress adr) {
		if (p.predInfo != null){
			predInfo = p.predInfo;
		}
		if (p.succInfo != null){
			succInfo = p.succInfo;
			addRoute(succInfo);
		}
		if (p.hashRange != null){
			hashRange = p.hashRange;
		}
		

	}

	/** Handle a leave packet from a leaving DHT node.
	*  @param p is the received join packet
	*  @param adr is the socket address of the host that sent the leave packet
	*
	*  If the leave packet is sent by this server, set the stopFlag.
	*  Otherwise firstly send the received leave packet to its successor,
	*  and then remove the routing entry with the senderInfo of the packet.
	*/
	public static void handleLeave(Packet p, InetSocketAddress adr) {
		if (p.senderInfo.equals(myInfo)){
			stopFlag = true;
			return;
		}
		// send the leave message to successor 
		p.send(sock, succInfo.left, debug);

		//remove the senderInfo from route table
		removeRoute(p.senderInfo);
	}
	
	/** Join an existing DHT.
	 *  @param predAdr is the socket address of a server in the DHT,
	 *  
	 *	This method handles the case where the current DHT server wants to join an existing server.
	 *  The DHT server will send a packet to the server with an address specified as predAdr and wait until
	 *  the packet is relayed back to it with the the hash range it is reponsible for and the successor.
	 */
	public static void join(InetSocketAddress predAdr) {
		//your code here
		//send the packet.
		Packet joinMsg = new Packet ();
		joinMsg.tag = sendTag++;
		joinMsg.type = "join";
		joinMsg.send(sock,predAdr,debug);
		Packet replyPkt = new Packet();
		//wait for the reply.
		while (! (replyPkt.receive(sock, debug).equals(predAdr)) ){
			//do nothing.
		}
		
		//error checking
		if(!replyPkt.check() || !replyPkt.type.equals("success")){
			System.out.println("failed to join DHT server becuz"+replyPkt.reason);
			System.exit(1);
		}
		hashRange = replyPkt.hashRange;
		succInfo = replyPkt.succInfo;
		predInfo= replyPkt.predInfo;
		myInfo=	new 
			    Pair<InetSocketAddress,Integer>(myAdr,hashRange.left);
		//update the succInfo with new hashRange.
		addRoute(succInfo);
		
	}
	
	/** Handle a join packet from a prospective DHT node.
	 *  @param p is the received join packet
	 *  @param succAdr is the socket address of the host that
	 *  sent the join packet (the new successor)
	 *
	 *  This method will handle the join requests from the new DHT server by first splitting its top hash range
	 *  to the new server, then set the new successors of the new server and itself and finally send transfer packets to
	 *  the new server to
	 */
	public static void handleJoin(Packet p, InetSocketAddress succAdr) {
		// your code here
		//First splits the top half range to the new successor.
		int newRight=(hashRange.right-hashRange.left)/2;
		//determine the difference and then divide the difference
		//in order to prevent overflow.
		newRight = newRight+hashRange.left;
		p.hashRange = new Pair<Integer,Integer>(newRight+1,hashRange.right);
		hashRange.right=newRight;
		p.type="success";
		
		//set the new server to the new successor.
		p.succInfo=succInfo;

		succInfo=new Pair<InetSocketAddress,Integer>(succAdr,p.hashRange.left);
		p.predInfo=myInfo;

		addRoute(succInfo);
		p.send(sock, succAdr, debug);
		//transfer the pairs that are now managed by the new server to the new server.
		Iterator pairIterator = map.entrySet().iterator();
		while (pairIterator.hasNext()){
			Entry current= (Entry) pairIterator.next();
			String key =(String)current.getKey();
			int currentHash = hashit(key);
			if(currentHash<= p.hashRange.right && currentHash>=p.hashRange.left){
				Packet transferPkt = new Packet();
				transferPkt.type="transfer";
				transferPkt.tag=sendTag++;
				transferPkt.key= key;
				transferPkt.val= (String) current.getValue();
				transferPkt.send(sock,succAdr , debug);
				
				//remove the the transfered pkts from the server.
				map.remove(key);
			}
		}		
	
	}
	
	/** Handle a get packet.
	 *  @param p is a get packet
	 *  @param senderAdr is the the socket address of the sender
	 *
	 *  This method responds to get requests that ask for key value pairs from DHT servers. When a Dht server gets a get request,
	 *  it will first check the local map using the hash value of the key to see if it is in charge of the key. If it, it will 
	 *  look it up using the local map and send a direct response. If it is not responsible, the server will then check the local cache.
	 *  If local cache does not have it either, it will forward the request to other Dht servers.
	 */
	public static void handleGet(Packet p, InetSocketAddress senderAdr) {
		// this version is incomplete; you will have to extend
		// it to support caching
		InetSocketAddress replyAdr;
		int hash = hashit(p.key);
		int left = hashRange.left.intValue();
		int right = hashRange.right.intValue();

		if (left <= hash && hash <= right) {
			// respond to request using map
			if (p.relayAdr != null) {
				replyAdr = p.relayAdr;
				p.senderInfo = new 
				    Pair<InetSocketAddress,Integer>(myAdr,left);
			} else {
				replyAdr = senderAdr;
			}
			if (map.containsKey(p.key)) {
				p.type = "success"; p.val = map.get(p.key);
			} else {
				p.type = "no match";
			}
			p.send(sock,replyAdr,debug);
		} 
		//check the local cache if the hash value is not managed by this server.
		else if(cacheOn && cache.containsKey(p.key)){
			p.val = cache.get(p.key);
			//check where to send the response back to.
			if(p.relayAdr == (null)){
				replyAdr = senderAdr;
			}
			else{
				replyAdr = p.relayAdr;
				p.senderInfo=new 
					    Pair<InetSocketAddress,Integer>(myAdr,left);
			}
			p.type="success";
			p.send(sock, replyAdr, debug);
		}
		else {
			// forward around DHT
			if (p.relayAdr == null) {
				p.relayAdr = myAdr; p.clientAdr = senderAdr;
			}
			forward(p,hash);
		}
	}
	
	/** Handle a put packet.
	 *  @param p is a put packet
	 *  @param senderAdr is the the socket address of the sender
	 *  
	 *	This method handles the put requests from clients. It first checks if the
	 *  hash value is within its own hashRange. If it does, it will add the key value pair
	 *  into the map and respond the client. If not, it will add clientAdr and relayAdr fields to
	 *  the packet and forward it to other servers.
	 */
	public static void handlePut(Packet p, InetSocketAddress senderAdr) {
		// your code here
		String key = p.key;
		int pktHash=hashit(key);
		if (pktHash>= hashRange.left && pktHash <= hashRange.right){
			//respond directly.
			if(p.val!=null){
				map.put(key, p.val);

			}
			else{
				//(omitting the value field causes the pair with the specified key to be removed). 
				map.remove((String)key);
			
			}
			
			p.type="success";
			
			//identifying if it is from a client or a forwarding server
			InetSocketAddress replyAdr;
			if(p.relayAdr==null){
				replyAdr=senderAdr;
			}
			else{
				replyAdr=p.relayAdr;
				p.senderInfo=new Pair<InetSocketAddress,Integer>(myAdr,hashRange.left);
			}
			
			p.send(sock, replyAdr, debug);
			
		}
		else{
			//not in charge. Forwarding the packets to other DHT server.
			if (cacheOn &&cache.containsKey(p.key)){
				cache.remove( p.key);
			}
			if(p.relayAdr == (null)){
				//I am the first server to receive this pkt from a client.
				p.relayAdr =myAdr;
				p.clientAdr=senderAdr;
			}
			forward(p,pktHash);
			
		}
	}

	/** Handle a transfer packet.
	 *  @param p is a transfer packet
	 *  @param senderAdr is the the address (ip:port) of the sender
	 *  
	 *	This method handles all the transfer packets from a server responding to a join method.The method will take in the
	 *  key value pair and put it into its map.It does not have to send a reply message.
	 *  
	 */
	public static void handleXfer(Packet p, InetSocketAddress senderAdr) {
		//put the transfered data into my map.
		map.put(p.key, p.val);
	}
	
	/** Handle a reply packet.
	 *  @param p is a reply packet, more specifically, a packet of type
	 *  "success", "failure" or "no match"
	 *  @param senderAdr is the the address (ip:port) of the sender
	 *  
	 *  when a server gets a response packet from another server, it assumes
     *  that it is the relay server and forward the packet to the client.
     *  It removes the clientAdr, relayAdr and senderInfo fields from the
     *  packet. If a "success" packet, store the (key, value) pair in cache
	 */
	public static void handleReply(Packet p, InetSocketAddress senderAdr) {
		// bold
        InetSocketAddress myAddr = p.clientAdr;
        p.clientAdr = null;
        p.relayAdr = null;
        rteTbl.add(p.senderInfo);
        p.senderInfo = null;
        p.send(sock, myAddr, debug);

        //cache the result in this server
        if (p.type.equals("success") && cacheOn &&
                p.key != null && p.val != null)
        {
            cache.put(p.key, p.val);
        }

	}
	
	/** Handle packets received from clients or other servers
	 *  @param p is a packet
	 *  @param senderAdr is the address (ip:port) of the sender
	 */
	public static void handlePacket(Packet p, InetSocketAddress senderAdr) {
		if (p.senderInfo != null & !p.type.equals("leave"))
			addRoute(p.senderInfo);
		if (p.type.equals("get")) {
			handleGet(p,senderAdr);
		} else if (p.type.equals("put")) {
			handlePut(p, senderAdr);
		} else if (p.type.equals("transfer")) {
			handleXfer(p, senderAdr);
		} else if (p.type.equals("success") ||
			   p.type.equals("no match") ||
		     	   p.type.equals("failure")) {
			handleReply(p, senderAdr);
		} else if (p.type.equals("join")) {
			handleJoin(p, senderAdr);
		} else if (p.type.equals("update")){
			handleUpdate(p, senderAdr);
		} else if (p.type.equals("leave")){
			handleLeave(p, senderAdr);
		}
	}
	
	/** Add an entry to the route tabe.
	 *  @param newRoute is a pair (addr,hash) where addr is the socket
	 *  address for some server and hash is the first hash in that
	 *  server's range
	 *
	 *  If the number of entries in the table exceeds the max
	 *  number allowed, the first entry that does not refer to
	 *  the successor of this server, is removed.
	 *  If debug is true and the set of stored routes does change,
	 *  print the string "rteTbl=" + rteTbl. (IMPORTANT)
	 */
	public static void addRoute(Pair<InetSocketAddress,Integer> newRoute){
		// // bold
  //       //check whether the rteTbl already has this entry, if has return
        for (Pair<InetSocketAddress,Integer> entry : rteTbl)
        {
            if (entry.equals(newRoute)) return;
        }
        rteTbl.add(newRoute);

        //check whether size exceeds the limit, remove entry
        if (rteTbl.size() > numRoutes)
        {
            Pair<InetSocketAddress,Integer> myDeletion = null;
            for (Pair<InetSocketAddress,Integer> entry : rteTbl)
            {
                if (!entry.equals(succInfo))
                {
                    myDeletion = entry;
                    break;
                }
            }
            rteTbl.remove(myDeletion);
            if (debug) System.out.println("rteTbl=" + rteTbl);
        }
        else
        {
            if (debug)
            	System.out.println("rteTbl=" + rteTbl);
        }
  //       //unbold

	}
	
	/** Remove an entry from the route tabe.
	 *  @param rmRoute is the route information for some server 
	 *  need to be removed from route table
	 *
	 *  If the route information exists in current entries, remove it.
	 *	Otherwise, do nothing.
	 *  If debug is true and the set of stored routes does change,
	 *  print the string "rteTbl=" + rteTbl. (IMPORTANT)
	 */
	public static void removeRoute(Pair<InetSocketAddress,Integer> rmRoute){
		// bold
        boolean myChange = false;
        for (Pair<InetSocketAddress,Integer> entry : rteTbl)
        {
            if (entry.equals(rmRoute))
            {
                myChange = true;
                rteTbl.remove(rmRoute);
            }
            break;
        }

        if (debug && myChange) System.out.println("rteTbl=" + rteTbl);
        //unbold
	}


	/** Forward a packet using the local routing table.
	 *  @param p is a packet to be forwarded
	 *  @param hash is the hash of the packet's key field
	 *
	 *  This method selects a server from its route table that is
	 *  "closest" to the target of this packet (based on hash).
	 *  If firstHash is the first hash in a server's range, then
	 *  we seek to minimize the difference hash-firstHash, where
	 *  the difference is interpreted modulo the range of hash values.
	 *  IMPORTANT POINT - handle "wrap-around" correctly. 
	 *  Once a server is selected, p is sent to that server.
	 */
	public static void forward(Packet p, int hash) {
		// bold

		//initialize variables.
        Pair<InetSocketAddress,Integer> myClosest = null;
        int myMin = Integer.MAX_VALUE;

        //initerate through the routeTable to find the next hop location.
        for (Pair<InetSocketAddress,Integer> entry : rteTbl)
        {
            int myDiff = hash - entry.right;

            if (myDiff<0){
            	myDiff+=Integer.MAX_VALUE;
            	myDiff++;
            	
            }
            if (myDiff >= 0)
            {
                if (myDiff < myMin || myClosest == null){
                    myClosest = entry;
                    myMin = myDiff;
                }
            }
        }
        p.send(sock, myClosest.left, debug);
        //unbold
	}
}
