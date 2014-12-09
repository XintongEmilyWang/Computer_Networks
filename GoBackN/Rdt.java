/** Reliable Data Transport class.
 *
 *  This class implements a reliable data transport service.
 *  It uses a go-back-N sliding window protocol on a packet basis.
 *
 *  An application layer thread provides new packet payloads to be
 *  sent using the provided send() method, and retrieves newly arrived
 *  payloads with the receive() method. Each application layer payload
 *  is sent as a separate UDP packet, along with a sequence number and
 *  a type flag that identifies a packet as a data packet or an
 *  acknowledgment. The sequence numbers are 15 bits.
 */

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class Rdt implements Runnable {
	private int wSize;	// protocol window size
	private long timeout;	// retransmission timeout in ns
	private Substrate sub;	// Substrate object for packet IO

	private ArrayBlockingQueue<String> fromSrc;
	private ArrayBlockingQueue<String> toSnk;

	// Sending structures and necessary information
	private Packet[] sendBuf; // not yet acked packets
	private short sendBase = 0;	// seq# of first packet in send window
	private short sendSeqNum = 0;	// next seq# after send window
	private short dupAcks = 0; // should only happen for sendBase-1 packet

	// Receiving structures and necessary information
	private Packet[] recvBuf; // undelivered packets
	private short recvBase = 0;  // seq# of oldest undelivered packet (to application)
	private short expSeqNum = 0;	// seq# of packet we expect to receive (from substrate)
	private short lastRcvd = -1; // last packet received properly

	// Time keeping variabels
	private long now = 0;		// current time (relative to t0)
	private long sendAgain = 0;	// time when we send all unacked packets

	private Thread myThread;
	private boolean quit;
    //boolean tag for stopping timer
	private boolean ifTimerStopped;
	/** Initialize a new Rdt object.
	 *  @param wSize is the window size used by protocol; the sequence #
	 *  space is twice the window size
	 *  @param timeout is the time to wait before retransmitting
	 *  @param sub is a reference to the Substrate object that this object
	 *  uses to handle the socket IO
	 */
	Rdt(int wSize, double timeout, Substrate sub) 
	{
		this.wSize = Math.min(wSize,(1 << 14) - 1);
		this.timeout = ((long) (timeout * 1000000000)); // sec to ns
		this.sub = sub;

		// create queues for application layer interface
		fromSrc = new ArrayBlockingQueue<String>(1000,true);
		toSnk = new ArrayBlockingQueue<String>(1000,true);
		quit = false;

		sendBuf = new Packet[2*wSize];
		recvBuf = new Packet[2*wSize];
		ifTimerStopped= false;
	}

	/** Start the Rdt running. */
	public void start() throws Exception {
		myThread = new Thread(this); myThread.start();
	}

	/** Stop the Rdt.  */
	public void stop() throws Exception { quit = true; myThread.join(); }

	/** Increment sequence number, handling wrap-around.
	 *  @param x is a sequence number
	 *  @return next sequence number after x
	 */
	private short incr(short x) {
		x++; return (x < 2*wSize ? x : 0);
	}

    /** Decrement sequence number, handling wrap-around.
     *  @param x is a sequence number
     *  @return previous sequence number before x
     */
	private short drc(short x) {
		x--; return (short) (x >= (short)0  ? x : (x + 2 * wSize));
	}
	/** Compute the difference between two sequence numbers,
	 *  accounting for "wrap-around"
	 *  @param x is a sequence number
	 *  @param y is another sequence number
	 *  @return difference, assuming x is "clockwise" from y
	 */
	private int diff(short x, short y) {
		return (x >= y ? x-y : (x + 2*wSize) - y);
	}

	/** Main thread for the Rdt object.
	 *
	 *  Inserts payloads received from the application layer into
	 *  packets, and sends them to the substrate. The packets include
	 *  the number of packets and chars sent so far (including the
	 *  current packet). It also takes packets received from
	 *  the substrate and sends the extracted payloads
	 *  up to the application layer. To ensure that packets are
	 *  delivered reliably and in-order, using a sliding
	 *  window protocol with the go-back-N feature.
	 */
	public void run() {
		long t0 = System.nanoTime();
		now = 0;		// current time (relative to t0)
        /* we still have un-acked packets */
		while (!quit || sendBuf[sendBase]!=null) {
			now = System.nanoTime() - t0;
			// if receive buffer has a packet that can be
			//    delivered, deliver it to sink

			// else if the substrate has an incoming packet
			//      get the packet from the substrate and process it
			// 	if it's a data packet, ack it and add it
			//	   to receive buffer as appropriate
			//	if it's an ack, update the send buffer and
			//	   related data as appropriate
			//	   reset the timer if necessary

			// else if the resend timer has expired, re-send all
			//      packets in the window and reset the timer

			// else if there is a message from the source waiting
			//      to be sent and the send window is not full
			//	and the substrate can accept a packet
			//      create a packet containing the message,
			//	and send it, after updating the send buffer
			//	and related data

			// else nothing to do, so sleep for 1 ms

            //still have unprocessed payload.
			if(recvBuf[recvBase]!=null){
				String load = recvBuf[recvBase].payload;
				if(toSnk.remainingCapacity()>0){
					toSnk.add(load);
					recvBase=incr(recvBase);
				}
				recvBuf[recvBase]=null;
			}
			else if (sub.incoming()){
				Packet p = sub.receive();
				//check if the packet is a payload loaded p or just an ack.
				if (p.type == 0){
					//check if the receive buffer is full, then update the receive buffer.
					//send back an ack.
					Packet ackSub = new Packet();
					ackSub.type=1;
					
					if(expSeqNum==p.seqNum && recvBuf[p.seqNum] == null){
						recvBuf[p.seqNum]=p;
						lastRcvd=p.seqNum;
						ackSub.seqNum= p.seqNum;
						expSeqNum = incr(expSeqNum);
					}
					else{
						if (lastRcvd!= -1){
							ackSub.seqNum=lastRcvd;
						}
					}
					if (lastRcvd !=-1){
						sub.send(ackSub);
					}
				}
				else if (p.type == 1){
					//receive an ack, check for duplicate, then update the sendBuffer.
					short base =sendBase;
					if(p.seqNum == drc(base)){
						dupAcks++;
						if(dupAcks==3){
							//resend all
							short index = sendBase;
							while (index != incr(sendSeqNum)
                                    && sendBuf[index]!=null){
								sub.send(sendBuf[index]);
								index=incr(index);
							}
							sendAgain= System.nanoTime()+timeout-t0;
							dupAcks=0;
						}
					}
					if(diff(p.seqNum,sendBase)<diff(sendSeqNum,sendBase)
                            && sendBuf[p.seqNum]!= null ){
						//cumulative acks. Clear out all the packets in the buffer before the ack.
						short curSeq=sendBase;
						sendBuf[p.seqNum]=null;
						while(curSeq!=p.seqNum){
							sendBuf[curSeq]=null;
							curSeq= incr(curSeq);
						}
						//reset the sendbase and timer.
						sendBase= incr(p.seqNum);
						
					    sendAgain= System.nanoTime()+timeout-t0;
					    dupAcks=0;
					    if (sendBuf[sendBase] == null){
					    	ifTimerStopped=true;
					    }

					}
				}			
			}
			//the timer expires.

			else if (now > sendAgain && sendAgain!=0 && !ifTimerStopped){
				//resend all the packets from sendBase up to the one before sendSeqNum.
				short index = sendBase;
				while (index != incr(sendSeqNum) && sendBuf[index]!=null){
					sub.send(sendBuf[index]);
					index = incr(index);
				}
				//reset timer.
				sendAgain= System.nanoTime()+timeout-t0;
				dupAcks=0;
			}
			
			else if (!fromSrc.isEmpty() &&
                    diff(sendSeqNum, sendBase) <=wSize && sub.ready() ){
				//create a new packet to be sent.
				Packet p = new Packet ();
				p.payload = fromSrc.poll();
				p.type =0;
				
				p.seqNum=sendSeqNum;
				//update the send buffer
				sendBuf[sendSeqNum]=p;
				sub.send(p);
				//update the next seq to be sent.
                sendSeqNum=incr(sendSeqNum);
				sendAgain= System.nanoTime()+timeout-t0;
				ifTimerStopped = false;
			}
			else{
				try{
				Thread.sleep(1);
				}
				catch (Exception e){
					System.err.println(e.toString());
				}
			}
		}
	}

	/** Send a message to peer.
	 *  @param message is a string to be sent to the peer
	 */
	public void send(String message) {
		try {
			fromSrc.put(message);
		} catch(Exception e) {
			System.err.println("Rdt:send: put exception" + e);
			System.exit(1);
		}
	}
		
	/** Test if Rdt is ready to send a message.
	 *  @return true if Rdt is ready
	 */
	public boolean ready() { return fromSrc.remainingCapacity() > 0; }

	/** Get an incoming message.
	 *  @return next message
	 */
	public String receive() {
		String s = null;
		try {
			s = toSnk.take();
		} catch(Exception e) {
			System.err.println("Rdt:send: take exception" + e);
			System.exit(1);
		}
		return s;
	}
	
	/** Test for the presence of an incoming message.
	 *  @return true if there is an incoming message
	 */
	public boolean incoming() { return toSnk.size() > 0; }
}
