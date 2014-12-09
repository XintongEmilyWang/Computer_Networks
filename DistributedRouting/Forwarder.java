import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Forwarder for an overlay IP router.
 *
 *  This class implements a basic packet forwarder for a simplified
 *  overlay IP router. It runs as a separate thread.
 *
 *  An application layer thread provides new packet payloads to be
 *  sent using the provided send() method, and retrieves newly arrived
 *  payloads with the receive() method. Each application layer payload
 *  is sent as a separate packet, where each packet includes a protocol
 *  field, a ttl, a source address and a destination address.
 */
public class Forwarder implements Runnable {
	private int myIp;	// this node's ip address in overlay
	private int debug;	// controls amount of debugging output
	private Substrate sub;	// Substrate object for packet IO
	private double now;	// current time in ns
	private final double sec = 1000000000; // # of ns in a second

	// forwarding table maps contains (prefix, link#) pairs
	private ArrayList<Pair<Prefix,Integer>> fwdTbl;

	// queues for communicating with SrcSnk
	private ArrayBlockingQueue<Packet> fromSrc;
	private ArrayBlockingQueue<Packet> toSnk;

	// queues for communicating with Router
	private ArrayBlockingQueue<Pair<Packet,Integer>> fromRtr;
	private ArrayBlockingQueue<Pair<Packet,Integer>> toRtr;

	private Thread myThread;
	private boolean quit;

	/** Initialize a new Forwarder object.
	 *  @param myIp is this node's IP address in the overlay network,
	 *  expressed as a raw integer.
	 *  @param sub is a reference to the Substrate object that this object
	 *  uses to handle the socket IO
	 *  @param debug controls the amount of debugging output
	 */
	Forwarder(int myIp, Substrate sub, int debug) {
		this.myIp = myIp; this.sub = sub; this.debug = debug;

		// intialize forwarding table with a default route to link 0
		fwdTbl = new ArrayList<Pair<Prefix,Integer>>();
		fwdTbl.add(new Pair<Prefix,Integer>(new Prefix(0,0), 0));

		// create queues for SrcSnk and Router
		fromSrc = new ArrayBlockingQueue<Packet>(1000,true);
		toSnk = new ArrayBlockingQueue<Packet>(1000,true);
		fromRtr = new
			  ArrayBlockingQueue<Pair<Packet,Integer>>(1000,true);
		toRtr = new
			ArrayBlockingQueue<Pair<Packet,Integer>>(1000,true);
		quit = false;
	}

	/** Start the Forwarder running. */
	public void start() throws Exception {
		myThread = new Thread(this); myThread.start();
	}

	/** Terminate the Forwarder.  */
	public void stop() throws Exception { quit = true; myThread.join(); }

	/** This is the main thread for the Forwarder object.
	 *
	 *  The run method defines the main chore of the forwarder thread. It will handle the packets
	 *  that the substrate receives and decides where to forward the packets to. The
	 *  forwarder also handles the packets that the router or the SrcSnk want to send out
	 *  with substrate by looking up the forwarding table and passing them to the next hop if
	 *  necssary.
	 */
	public void run() {
		now = 0; double t0 = System.nanoTime()/sec;

		while (!quit) {
			now = System.nanoTime()/sec - t0;
			Pair<Packet,Integer> pktBundle;
			Packet overlayP;
			int link = -1;

			// if the Substrate has an incoming packet
			if (sub.incoming()){
				pktBundle= sub.receive();
				overlayP=pktBundle.left;
				overlayP.ttl--;
				link=pktBundle.right;
	            //       if it's addressed to this overlay router,
				if(overlayP.destAdr == myIp ){
		            //               send to the SrcSnk or the Router
					if(overlayP.protocol ==1){
						//send the overlay packet to src sink
						toSnk.offer(overlayP);
					}
					else if (overlayP.protocol == 2){
						//send the onl packet to router.
						toRtr.offer(pktBundle);
					}
				}
	            // else if the packet has not expired
	            // see if it is possible to forward it to the next hop	
				else if( overlayP.ttl>0){
					int forwardLink = lookup(overlayP.destAdr);
					if (sub.ready(forwardLink)  &&forwardLink>=0 ){
						sub.send(overlayP, forwardLink);
					}
				}
			}
            // 		else if we have a packet from the Router to send
            //       send it to the Substrate			
			else if (fromRtr.size()>0){
				pktBundle=fromRtr.peek();
				overlayP=pktBundle.left;
				link=pktBundle.right;
				//check if the substrate is ready to send on that link
				if (sub.ready(link)){
					try{
						//remove the head and wait as long as it takes.
						fromRtr.take();
					}
					catch (InterruptedException e){
						System.out.println("cant remove the head of the fromRtr queue in order to send it");
					}
					sub.send(overlayP,link);
				}
			}
            // 		else if we have a payload from the SrcSnk to send
			else if (fromSrc.size()>0){
				overlayP = fromSrc.peek();
	            //       lookup the outgoing link using dest IP address
				int outLink = lookup (overlayP.destAdr);
				if(sub.ready(outLink)){
					try{
						//remove the head (can wait as long as it takes).
						fromSrc.take();
					}
					catch (InterruptedException e){
						System.out.println("cant remove the head of the fromSrc queue in order to send it");
					}		
		            //       format a packet containing the payload and
		            //       pass it to the Substrate
					sub.send(overlayP, outLink);
				}
			}
            // 		else, nothing to do, so take a nap
			else{
				try {
					//sleep 1ms.
					Thread.sleep(1);
					} 
				catch(InterruptedException e) {
					e.printStackTrace();
					System.err.println("can't sleep");
				}
			}
		}
	}

	/** Add a route to the forwarding table.
	 *
	 *  @param nuPrefix is a prefix to be added
	 *  @param nuLnk is the number of the link on which to forward
	 *  packets matching the prefix
	 *
	 *  If the table already contains a route with the specified
	 *  prefix, the route is updated to use nuLnk. Otherwise,
	 *  a route is added.
	 *
	 *  If debug>0, print the forwarding table when done
	 */
	public synchronized void addRoute(Prefix nuPrefix, int nuLnk) {
		// TODO
		// if table contains an entry with the same prefix,
		// just update the link; otherwise add an entry
		for (Pair<Prefix, Integer> entry : fwdTbl){
			if	(entry.left.equals( nuPrefix)){
				entry.right= nuLnk;
				if(debug>0){
					printTable();
				}
				return;
			}
		}
		fwdTbl.add(new Pair<Prefix,Integer>(nuPrefix,nuLnk));
		if(debug>0){
			printTable();
		}
	}

	/** Print the contents of the forwarding table. */
	public synchronized void printTable() {
		String s = String.format("Forwarding table (%.3f)\n",now);
		for (Pair<Prefix,Integer> rte : fwdTbl)
			s += String.format("%s %s\n", rte.left, rte.right);
		System.out.println(s);
	}

	/** Lookup route in fwding table.
	 *
	 *  @param ip is an integer representing an IP address to lookup
	 *  @return nextHop link number or -1, if no matching entry.
	 */
	private synchronized int lookup(int ip) {
		// TODO
		int matchedLink=-1;
		int max=-1;
		for (Pair<Prefix, Integer> entry : fwdTbl){
			if(entry.left.matches(ip) && entry.left.leng >max){
				max=entry.left.leng;
				matchedLink=entry.right;
			}
		}
		return matchedLink;
	}

	/** Send a message to another overlay host.
	 *  @param message is a string to be sent to the peer
	 */
	public void send(String payload, String destAdr) {
		Packet p = new Packet();
		p.srcAdr = myIp; p.destAdr = Util.string2ip(destAdr);
		p.protocol = 1; p.ttl = 100;
		p.payload = payload;
		try {
			fromSrc.put(p);
		} catch(Exception e) {
			System.err.println("Forwarder:send: put exception" + e);
			System.exit(1);
		}
	}
		
	/** Test if Forwarder is ready to send a message.
	 *  @return true if Forwarder is ready
	 */
	public boolean ready() { return fromSrc.remainingCapacity() > 0; }

	/** Get an incoming message.
	 *  @return next message
	 */
	public Pair<String,String> receive() {
		Packet p = null;
		try {
			p = toSnk.take();
		} catch(Exception e) {
			System.err.println("Forwarder:send: take exception" +e);
			System.exit(1);
		}
		return new Pair<String,String>(
				p.payload,Util.ip2string(p.srcAdr));
	}
	
	/** Test for the presence of an incoming message.
	 *  @return true if there is an incoming message
	 */
	public boolean incoming() { return toSnk.size() > 0; }

	// the following methods are used by the Router

	/** Send a message to another overlay Router.
	 *  @param p is a packet to be sent to another overlay node
	 *  @param lnk is the number of the link the packet should be
	 *  forwarded on
	 */
	public void sendPkt(Packet p, int lnk) {
		Pair<Packet,Integer> pp = new Pair<Packet,Integer>(p,lnk);
		try {
			fromRtr.put(pp);
		} catch(Exception e) {
			System.err.println("Forwarder:sendPkt: cannot write"
					    + " to fromRtr " + e);
			System.exit(1);
		}
		// debug for print pkt
		if (debug > 2) printPkt(p, lnk, 0);
	}
		
	/** Test if Forwarder is ready to send a packet from Router.
	 *  @return true if Forwarder is ready
	 */
	public boolean ready4pkt() { return fromRtr.remainingCapacity() > 0; }

	/** Get an incoming packet.
	 *  @return a Pair containing the next packet for the Router,
	 *  including the number of the link on which it arrived
	 */
	public Pair<Packet,Integer> receivePkt() {
		Pair<Packet,Integer> pp = null;
		try {
			pp = toRtr.take();
		} catch(Exception e) {
			System.err.println("Forwarder:receivePkt: cannot read"
					    + " from toRtr " + e);
			System.exit(1);
		}
		return pp;
	}
	
	/** Test for the presence of an incoming packet for Router.
	 *  @return true if there is an incoming packet
	 */
	public boolean incomingPkt() { return toRtr.size() > 0; }

	public void printPkt(Packet p, int lnk, int inout){
		// incoming pkt
		String s;
		if (inout == 1)
			s = String.format("Receive");
		else
			s = String.format("Send");
		s += String.format("Pkt from %s to %s through lnk %d\n", 
				Util.ip2string(p.srcAdr), Util.ip2string(p.destAdr), lnk);
		s += String.format("%s\n", p.payload);
		System.out.println(s);
	}
}
