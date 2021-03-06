package net.i2p.router.transport.udp;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.RouterAddress;
import net.i2p.data.RouterInfo;
import net.i2p.data.SessionKey;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.RouterContext;
import static net.i2p.router.transport.udp.PeerTestState.Role.*;
import net.i2p.util.Addresses;
import net.i2p.util.Log;
import net.i2p.util.SimpleScheduler;
import net.i2p.util.SimpleTimer;

/**
 *  Entry points are runTest() to start a new test as Alice,
 *  and receiveTest() for all received test packets.
 *
 *  From udp.html on the website:

<p>The automation of collaborative reachability testing for peers is
enabled by a sequence of PeerTest messages.  With its proper 
execution, a peer will be able to determine their own reachability
and may update its behavior accordingly.  The testing process is 
quite simple:</p>

<pre>
        Alice                  Bob                  Charlie

    runTest()
    sendTestToBob()     receiveFromAliceAsBob()
    PeerTest -------------------&gt;

                        sendTestToCharlie()       receiveFromBobAsCharlie()
                             PeerTest--------------------&gt;

                        receiveFromCharlieAsBob()
                                &lt;-------------------PeerTest

    receiveTestReply()
         &lt;-------------------PeerTest

    receiveTestReply()
         &lt;------------------------------------------PeerTest

                                                  receiveFromAliceAsCharlie()
    PeerTest------------------------------------------&gt;

    receiveTestReply()
         &lt;------------------------------------------PeerTest
</pre>

<p>Each of the PeerTest messages carry a nonce identifying the
test series itself, as initialized by Alice.  If Alice doesn't 
get a particular message that she expects, she will retransmit
accordingly, and based upon the data received or the messages
missing, she will know her reachability.  The various end states
that may be reached are as follows:</p>

<ul>
<li>If she doesn't receive a response from Bob, she will retransmit
up to a certain number of times, but if no response ever arrives,
she will know that her firewall or NAT is somehow misconfigured, 
rejecting all inbound UDP packets even in direct response to an
outbound packet.  Alternately, Bob may be down or unable to get 
Charlie to reply.</li>

<li>If Alice doesn't receive a PeerTest message with the 
expected nonce from a third party (Charlie), she will retransmit
her initial request to Bob up to a certain number of times, even
if she has received Bob's reply already.  If Charlie's first message 
still doesn't get through but Bob's does, she knows that she is
behind a NAT or firewall that is rejecting unsolicited connection
attempts and that port forwarding is not operating properly (the
IP and port that Bob offered up should be forwarded).</li>

<li>If Alice receives Bob's PeerTest message and both of Charlie's
PeerTest messages but the enclosed IP and port numbers in Bob's 
and Charlie's second messages don't match, she knows that she is 
behind a symmetric NAT, rewriting all of her outbound packets with
different 'from' ports for each peer contacted.  She will need to
explicitly forward a port and always have that port exposed for 
remote connectivity, ignoring further port discovery.</li>

<li>If Alice receives Charlie's first message but not his second,
she will retransmit her PeerTest message to Charlie up to a 
certain number of times, but if no response is received she knows
that Charlie is either confused or no longer online.</li>
</ul>

<p>Alice should choose Bob arbitrarily from known peers who seem
to be capable of participating in peer tests.  Bob in turn should
choose Charlie arbitrarily from peers that he knows who seem to be
capable of participating in peer tests and who are on a different
IP from both Bob and Alice.  If the first error condition occurs
(Alice doesn't get PeerTest messages from Bob), Alice may decide
to designate a new peer as Bob and try again with a different nonce.</p>

<p>Alice's introduction key is included in all of the PeerTest 
messages so that she doesn't need to already have an established
session with Bob and so that Charlie can contact her without knowing
any additional information.  Alice may go on to establish a session
with either Bob or Charlie, but it is not required.</p>

 */
class PeerTestManager {
    private final RouterContext _context;
    private final Log _log;
    private final UDPTransport _transport;
    private final PacketBuilder _packetBuilder;
    /** map of Long(nonce) to PeerTestState for tests currently in progress (as Bob/Charlie) */
    private final Map<Long, PeerTestState> _activeTests;
    /** current test we are running (as Alice), or null */
    private PeerTestState _currentTest;
    private boolean _currentTestComplete;
    /** as Alice */
    private final Queue<Long> _recentTests;
    private final IPThrottler _throttle;
    
    private static final int MAX_RELAYED_PER_TEST_ALICE = 9;
    private static final int MAX_RELAYED_PER_TEST_BOB = 6;
    private static final int MAX_RELAYED_PER_TEST_CHARLIE = 6;
    
    /** longest we will keep track of a Charlie nonce for */
    private static final int MAX_CHARLIE_LIFETIME = 15*1000;

    /** as Bob/Charlie */
    private static final int MAX_ACTIVE_TESTS = 20;
    private static final int MAX_RECENT_TESTS = 40;

    /** for the throttler */
    private static final int MAX_PER_IP = 12;
    private static final long THROTTLE_CLEAN_TIME = 10*60*1000;

    /** initial - ContinueTest adds backoff */
    private static final int RESEND_TIMEOUT = 4*1000;
    private static final int MAX_TEST_TIME = 30*1000;
    private static final long MAX_NONCE = (1l << 32) - 1l;

    /**
     *  Have seen peer tests (as Alice) get stuck (_currentTest != null)
     *  so I've thrown some synchronizization on the methods;
     *  don't know the root cause or whether this fixes it
     */
    public PeerTestManager(RouterContext context, UDPTransport transport) {
        _context = context;
        _transport = transport;
        _log = context.logManager().getLog(PeerTestManager.class);
        _activeTests = new ConcurrentHashMap();
        _recentTests = new LinkedBlockingQueue();
        _packetBuilder = new PacketBuilder(context, transport);
        _throttle = new IPThrottler(MAX_PER_IP, THROTTLE_CLEAN_TIME);
        _context.statManager().createRateStat("udp.statusKnownCharlie", "How often the bob we pick passes us to a charlie we already have a session with?", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.receiveTestReply", "How often we get a reply to our peer test?", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.receiveTest", "How often we get a packet requesting us to participate in a peer test?", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.testBadIP", "Received IP or port was bad", "udp", UDPTransport.RATES);
    }

    /**
     *  The next few methods are for when we are Alice
     */
    public synchronized void runTest(InetAddress bobIP, int bobPort, SessionKey bobCipherKey, SessionKey bobMACKey) {
        if (_currentTest != null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("We are already running a test: " + _currentTest + ", aborting test with bob = " + bobIP);
            return;
        }
        if (DataHelper.eq(bobIP.getAddress(), 0, _transport.getExternalIP(), 0, 2)) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Not running test with Bob too close to us " + bobIP);
            return;
        }
        PeerTestState test = new PeerTestState(ALICE,
                                               _context.random().nextLong(MAX_NONCE),
                                               _context.clock().now());
        test.setBobIP(bobIP);
        test.setBobPort(bobPort);
        test.setBobCipherKey(bobCipherKey);
        test.setBobMACKey(bobMACKey);
        test.setLastSendTime(test.getBeginTime());
        _currentTest = test;
        _currentTestComplete = false;
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Start new test: " + test);
        while (_recentTests.size() > MAX_RECENT_TESTS)
            _recentTests.poll();
        _recentTests.offer(Long.valueOf(test.getNonce()));
        
        test.incrementPacketsRelayed();
        sendTestToBob();
        
        _context.simpleScheduler().addEvent(new ContinueTest(test.getNonce()), RESEND_TIMEOUT);
    }
    
    private class ContinueTest implements SimpleTimer.TimedEvent {
        private final long _nonce;

        public ContinueTest(long nonce) {
            _nonce = nonce;
        }

        public void timeReached() {
            synchronized (PeerTestManager.this) {
                PeerTestState state = _currentTest;
                if (state == null || state.getNonce() != _nonce) {
                    // already completed, possibly on to the next test
                    return;
                } else if (expired()) {
                    testComplete(true);
                } else if (_context.clock().now() - state.getLastSendTime() >= RESEND_TIMEOUT) {
                    int sent = state.incrementPacketsRelayed();
                    if (sent > MAX_RELAYED_PER_TEST_ALICE) {
                        testComplete(false);
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Sent too many packets: " + state);
                        return;
                    }
                    if (state.getReceiveBobTime() <= 0) {
                        // no message from Bob yet, send it again
                        sendTestToBob();
                    } else if (state.getReceiveCharlieTime() <= 0) {
                        // received from Bob, but no reply from Charlie.  send it to 
                        // Bob again so he pokes Charlie
                        sendTestToBob();
                    } else {
                        // received from both Bob and Charlie, but we haven't received a
                        // second message from Charlie yet
                        sendTestToCharlie();
                    }
                    // retx at 4, 10, 17, 25 elapsed time
                    _context.simpleScheduler().addEvent(ContinueTest.this, RESEND_TIMEOUT + (sent*1000));
                }
            }
        }
    }

    /** call from a synchronized method */
    private boolean expired() { 
        PeerTestState state = _currentTest;
        if (state != null)
            return state.getBeginTime() + MAX_TEST_TIME < _context.clock().now(); 
        else
            return true;
    }
    
    /** call from a synchronized method */
    private void sendTestToBob() {
        PeerTestState test = _currentTest;
        if (!expired()) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Sending test to Bob: " + test);
            _transport.send(_packetBuilder.buildPeerTestFromAlice(test.getBobIP(), test.getBobPort(), test.getBobCipherKey(), test.getBobMACKey(), //_bobIntroKey, 
                            test.getNonce(), _transport.getIntroKey()));
        } else {
            _currentTest = null;
        }
    }

    /** call from a synchronized method */
    private void sendTestToCharlie() {
        PeerTestState test = _currentTest;
        if (!expired()) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Sending test to Charlie: " + test);
            _transport.send(_packetBuilder.buildPeerTestFromAlice(test.getCharlieIP(), test.getCharliePort(), test.getCharlieIntroKey(), 
                            test.getNonce(), _transport.getIntroKey()));
        } else {
            _currentTest = null;
        }
    }
    
    /**
     * If we have sent a packet to charlie within the last 10 minutes, ignore any test 
     * results we get from them, as our NAT will have poked a hole anyway
     * NAT idle timeouts vary widely, from 30s to 10m or more.
     * Set this too high and a high-traffic router may rarely get a good test result.
     * Set it too low and a router will think it is reachable when it isn't.
     * Maybe a router should need two consecutive OK results before believing it?
     *
     */
    private static final long CHARLIE_RECENT_PERIOD = 10*60*1000;

    /**
     * Receive a PeerTest message which contains the correct nonce for our current 
     * test. We are Alice.
     */
    private synchronized void receiveTestReply(RemoteHostId from, UDPPacketReader.PeerTestReader testInfo) {
        _context.statManager().addRateData("udp.receiveTestReply", 1);
        PeerTestState test = _currentTest;
        if (expired())
            return;
        if (_currentTestComplete)
            return;
        if ( (DataHelper.eq(from.getIP(), test.getBobIP().getAddress())) && (from.getPort() == test.getBobPort()) ) {
            // The reply is from Bob

            int ipSize = testInfo.readIPSize();
            if (ipSize != 4 && ipSize != 16) {
                // There appears to be a bug where Bob is sending us a zero-length IP.
                // We could proceed without setting the IP, but then when Charlie
                // sends us his message, we will think we are behind a symmetric NAT
                // because the Bob and Charlie IPs won't match.
                // So for now we just return and pretend we didn't hear from Bob at all.
                // Which is essentially what catching the uhe below did,
                // but without the error message to the log.
                // To do: fix the bug.
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Bad IP length " + ipSize +
                               " from bob's reply: " + from + ", " + testInfo);
                return;
            }
            byte ip[] = new byte[ipSize];
            testInfo.readIP(ip, 0);
            try {
                InetAddress addr = InetAddress.getByAddress(ip);
                test.setAliceIP(addr);
                test.setReceiveBobTime(_context.clock().now());
                int testPort = testInfo.readPort();
                if (testPort == 0)
                    throw new UnknownHostException("port 0");
                test.setAlicePort(testPort);

                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Receive test reply from Bob: " + test);
                if (test.getAlicePortFromCharlie() > 0)
                    testComplete(false);
            } catch (UnknownHostException uhe) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Unable to get our IP (length " + ipSize +
                               ") from bob's reply: " + from + ", " + testInfo, uhe);
                _context.statManager().addRateData("udp.testBadIP", 1);
            }
        } else {
            // The reply is from Charlie

            PeerState charlieSession = _transport.getPeerState(from);
            long recentBegin = _context.clock().now() - CHARLIE_RECENT_PERIOD;
            if ( (charlieSession != null) && 
                 ( (charlieSession.getLastACKSend() > recentBegin) ||
                   (charlieSession.getLastSendTime() > recentBegin) ) ) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Bob chose a charlie we already have a session to, cancelling the test and rerunning (bob: " 
                              + _currentTest + ", charlie: " + from + ")");
                // why are we doing this instead of calling testComplete() ?
                _currentTestComplete = true;
                _context.statManager().addRateData("udp.statusKnownCharlie", 1);
                honorStatus(CommSystemFacade.STATUS_UNKNOWN);
                _currentTest = null;
                return;
            }
    
            if (test.getReceiveCharlieTime() > 0) {
                // this is our second charlie, yay!
                try {
                    int testPort = testInfo.readPort();
                    if (testPort == 0)
                        throw new UnknownHostException("port 0");
                    test.setAlicePortFromCharlie(testPort);
                    byte ip[] = new byte[testInfo.readIPSize()];
                    testInfo.readIP(ip, 0);
                    InetAddress addr = InetAddress.getByAddress(ip);
                    test.setAliceIPFromCharlie(addr);
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Receive test reply from Charlie: " + test);
                    if (test.getReceiveBobTime() > 0)
                        testComplete(true);
                } catch (UnknownHostException uhe) {
                    if (_log.shouldLog(Log.ERROR))
                        _log.error("Charlie @ " + from + " said we were an invalid IP address: " + uhe.getMessage(), uhe);
                    _context.statManager().addRateData("udp.testBadIP", 1);
                }
            } else {
                if (test.incrementPacketsRelayed() > MAX_RELAYED_PER_TEST_ALICE) {
                    testComplete(false);
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Sent too many packets on the test: " + test);
                    return;
                }
                
                if (_log.shouldLog(Log.INFO) && charlieSession != null)
                    _log.info("Bob chose a charlie we last acked " + DataHelper.formatDuration(_context.clock().now() - charlieSession.getLastACKSend()) + " last sent " + DataHelper.formatDuration(_context.clock().now() - charlieSession.getLastSendTime()) + " (bob: " 
                              + _currentTest + ", charlie: " + from + ")");

                // ok, first charlie.  send 'em a packet
                test.setReceiveCharlieTime(_context.clock().now());
                SessionKey charlieIntroKey = new SessionKey(new byte[SessionKey.KEYSIZE_BYTES]);
                testInfo.readIntroKey(charlieIntroKey.getData(), 0);
                test.setCharlieIntroKey(charlieIntroKey);
                try {
                    test.setCharlieIP(InetAddress.getByAddress(from.getIP()));
                    test.setCharliePort(from.getPort());
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Receive test from Charlie: " + test);
                    sendTestToCharlie();
                } catch (UnknownHostException uhe) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Charlie's IP is b0rked: " + from + ": " + testInfo);
                    _context.statManager().addRateData("udp.testBadIP", 1);
                }
            }
        }
    }
    
    /**
     * Evaluate the info we have and act accordingly, since the test has either timed out or
     * we have successfully received the second PeerTest from a Charlie.
     *
     * @param forgetTest must be true to clear out this test and allow another
     *
     * call from a synchronized method
     */
    private void testComplete(boolean forgetTest) {
        _currentTestComplete = true;
        short status = -1;
        PeerTestState test = _currentTest;

        // Don't do this or we won't call honorStatus()
        // to set the status to UNKNOWN or REJECT_UNSOLICITED
        // if (expired()) { 
        //     _currentTest = null;
        //    return;
        // }

        if (test.getAlicePortFromCharlie() > 0) {
            // we received a second message from charlie
            if ( (test.getAlicePort() == test.getAlicePortFromCharlie()) &&
                 (test.getAliceIP() != null) && (test.getAliceIPFromCharlie() != null) &&
                 (test.getAliceIP().equals(test.getAliceIPFromCharlie())) ) {
                status = CommSystemFacade.STATUS_OK;
            } else {
                status = CommSystemFacade.STATUS_DIFFERENT;
            }
        } else if (test.getReceiveCharlieTime() > 0) {
            // we received only one message from charlie
            status = CommSystemFacade.STATUS_UNKNOWN;
        } else if (test.getReceiveBobTime() > 0) {
            // we received a message from bob but no messages from charlie
            status = CommSystemFacade.STATUS_REJECT_UNSOLICITED;
        } else {
            // we never received anything from bob - he is either down, 
            // ignoring us, or unable to get a Charlie to respond
            status = CommSystemFacade.STATUS_UNKNOWN;
        }
        
        if (_log.shouldLog(Log.INFO))
            _log.info("Test complete: " + test);
        
        honorStatus(status);
        if (forgetTest)
            _currentTest = null;
    }
    
    /**
     * Depending upon the status, fire off different events (using received port/ip/etc as 
     * necessary).
     *
     */
    private void honorStatus(short status) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Test results: status = " + status);
        _transport.setReachabilityStatus(status);
    }
    
    /**
     * Entry point for all incoming packets. Most of the source and dest validation is here.
     *
     * Receive a test message of some sort from the given peer, queueing up any packet
     * that should be sent in response, or if its a reply to our own current testing,
     * adjusting our test state.
     *
     * We could be Alice, Bob, or Charlie.
     */
    public void receiveTest(RemoteHostId from, UDPPacketReader reader) {
        _context.statManager().addRateData("udp.receiveTest", 1);
        byte[] fromIP = from.getIP();
        int fromPort = from.getPort();
        if (fromPort < 1024 || fromPort > 65535 ||
            (!_transport.isValid(fromIP)) ||
            DataHelper.eq(fromIP, 0, _transport.getExternalIP(), 0, 2) ||
            _context.blocklist().isBlocklisted(fromIP)) {
            // spoof check, and don't respond to privileged ports
            if (_log.shouldLog(Log.WARN))
                _log.warn("Invalid PeerTest address: " + Addresses.toString(fromIP, fromPort));
            _context.statManager().addRateData("udp.testBadIP", 1);
            return;
        }
        UDPPacketReader.PeerTestReader testInfo = reader.getPeerTestReader();
        byte testIP[] = null;
        int testPort = testInfo.readPort();

        if (testInfo.readIPSize() > 0) {
            testIP = new byte[testInfo.readIPSize()];
            testInfo.readIP(testIP, 0);
        }

        if ((testPort > 0 && (testPort < 1024 || testPort > 65535)) ||
            (testIP != null &&
                               ((!_transport.isValid(testIP)) ||
                                _context.blocklist().isBlocklisted(testIP)))) {
            // spoof check, and don't respond to privileged ports
            if (_log.shouldLog(Log.WARN))
                _log.warn("Invalid address in PeerTest: " + Addresses.toString(testIP, testPort));
            _context.statManager().addRateData("udp.testBadIP", 1);
            return;
        }

        // The from IP/port and message's IP/port are now validated.
        // EXCEPT that either the message's IP could be empty or the message's port could be 0.
        // Both of those cases should be checked in receiveXfromY() as appropriate.
        // Also, IP could be us, check is below.

        long nonce = testInfo.readNonce();
        PeerTestState test = _currentTest;
        if ( (test != null) && (test.getNonce() == nonce) ) {
            // we are Alice, we initiated the test
            receiveTestReply(from, testInfo);
            return;
        }

        // we are Bob or Charlie, we are helping Alice

        if (_throttle.shouldThrottle(fromIP)) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("PeerTest throttle from " + Addresses.toString(fromIP, fromPort));
            return;
        }

        // use the same counter for both from and to IPs
        if (testIP != null && _throttle.shouldThrottle(testIP)) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("PeerTest throttle to " + Addresses.toString(testIP, testPort));
            return;
        }

        Long lNonce = Long.valueOf(nonce);
        PeerTestState state = _activeTests.get(lNonce);

        if (testIP != null && DataHelper.eq(testIP, 0, _transport.getExternalIP(), 0, 2)) {
            // spoof check - have to do this after receiveTestReply(), since
            // the field should be us there.
            // Let's also eliminate anybody in the same /16
            if (_recentTests.contains(lNonce)) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Got delayed reply on nonce " + nonce +
                              " from: " + Addresses.toString(fromIP, fromPort));
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Nearby address in PeerTest: " + Addresses.toString(testIP, testPort) +
                              " from: " + Addresses.toString(fromIP, fromPort) +
                              " state? " + state);
                _context.statManager().addRateData("udp.testBadIP", 1);
            }
            return;
        }
        
        if (state == null) {
            // NEW TEST
            if ( (testIP == null) || (testPort <= 0) ) {
                // we are bob, since we haven't seen this nonce before AND its coming from alice
                if (_activeTests.size() >= MAX_ACTIVE_TESTS) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Too many active tests, droppping from Alice " + Addresses.toString(fromIP, fromPort));
                    return;
                }
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("test IP/port are blank coming from " + from + ", assuming we are Bob and they are alice");
                receiveFromAliceAsBob(from, testInfo, nonce, null);
            } else {
                if (_recentTests.contains(lNonce)) {
                    // ignore the packet, as its a holdover from a recently completed locally
                    // initiated test
                } else {
                    if (_activeTests.size() >= MAX_ACTIVE_TESTS) {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Too many active tests, droppping from Bob " + Addresses.toString(fromIP, fromPort));
                        return;
                    }
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("We are charlie, as the testIP/port is " + Addresses.toString(testIP, testPort) + " and the state is unknown for " + nonce);
                    // we are charlie, since alice never sends us her IP and port, only bob does (and,
                    // erm, we're not alice, since it isn't our nonce)
                    receiveFromBobAsCharlie(from, testInfo, nonce, null);
                }
            }
        } else {
            // EXISTING TEST
            if (state.getOurRole() == BOB) {
                if (DataHelper.eq(fromIP, state.getAliceIP().getAddress()) && 
                    (fromPort == state.getAlicePort()) ) {
                    receiveFromAliceAsBob(from, testInfo, nonce, state);
                } else if (DataHelper.eq(fromIP, state.getCharlieIP().getAddress()) && 
                           (fromPort == state.getCharliePort()) ) {
                    receiveFromCharlieAsBob(from, state);
                } else {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Received from a fourth party as bob!  alice: " + state.getAliceIP() + ", charlie: " + state.getCharlieIP() + ", dave: " + from);
                }
            } else if (state.getOurRole() == CHARLIE) {
                if ( (testIP == null) || (testPort <= 0) ) {
                    receiveFromAliceAsCharlie(from, testInfo, nonce, state);
                } else {
                    receiveFromBobAsCharlie(from, testInfo, nonce, state);
                }
            }
        }
    }
    
    // Below here are methods for when we are Bob or Charlie

    /**
     * The packet's IP/port does not match the IP/port included in the message, 
     * so we must be Charlie receiving a PeerTest from Bob.
     *  
     * @param state null if new
     */
    private void receiveFromBobAsCharlie(RemoteHostId from, UDPPacketReader.PeerTestReader testInfo, long nonce, PeerTestState state) {
        long now = _context.clock().now();
        boolean isNew = false;
        if (state == null) {
            isNew = true;
            state = new PeerTestState(CHARLIE, nonce, now);
        } else {
            if (state.getReceiveBobTime() > now - (RESEND_TIMEOUT / 2)) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Too soon, not retransmitting: " + state);
                return;
            }
        }

        // TODO should only do most of this if isNew
        int sz = testInfo.readIPSize();
        byte aliceIPData[] = new byte[sz];
        try {
            testInfo.readIP(aliceIPData, 0);
            int alicePort = testInfo.readPort();
            if (alicePort == 0)
                throw new UnknownHostException("port 0");
            InetAddress aliceIP = InetAddress.getByAddress(aliceIPData);
            InetAddress bobIP = InetAddress.getByAddress(from.getIP());
            SessionKey aliceIntroKey = new SessionKey(new byte[SessionKey.KEYSIZE_BYTES]);
            testInfo.readIntroKey(aliceIntroKey.getData(), 0);
         
            state.setAliceIP(aliceIP);
            state.setAlicePort(alicePort);
            state.setAliceIntroKey(aliceIntroKey);
            state.setBobIP(bobIP);
            state.setBobPort(from.getPort());
            state.setLastSendTime(now);
            state.setReceiveBobTime(now);
            
            PeerState bob = _transport.getPeerState(from);
            if (bob == null) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Received from bob (" + from + ") who hasn't established a session with us, refusing to help him test " + aliceIP +":" + alicePort);
                return;
            } else {
                state.setBobCipherKey(bob.getCurrentCipherKey());
                state.setBobMACKey(bob.getCurrentMACKey());
            }

            // we send two packets below, but increment just once
            if (state.incrementPacketsRelayed() > MAX_RELAYED_PER_TEST_CHARLIE) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Too many, not retransmitting: " + state);
                return;
            }
            
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Receive from Bob: " + state);
            
            if (isNew) {
                _activeTests.put(Long.valueOf(nonce), state);
                _context.simpleScheduler().addEvent(new RemoveTest(nonce), MAX_CHARLIE_LIFETIME);
            }

            UDPPacket packet = _packetBuilder.buildPeerTestToBob(bobIP, from.getPort(), aliceIP, alicePort, aliceIntroKey, nonce, state.getBobCipherKey(), state.getBobMACKey());
            _transport.send(packet);
            
            packet = _packetBuilder.buildPeerTestToAlice(aliceIP, alicePort, aliceIntroKey, _transport.getIntroKey(), nonce);
            _transport.send(packet);
        } catch (UnknownHostException uhe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unable to build the aliceIP from " + from + ", ip size: " + sz + " ip val: " + Base64.encode(aliceIPData), uhe);
            _context.statManager().addRateData("udp.testBadIP", 1);
        }
    }

    /**
     * The PeerTest message came from the peer referenced in the message (or there wasn't
     * any info in the message), plus we are not acting as Charlie (so we've got to be Bob).
     *
     * testInfo IP/port ignored
     * @param state null if new
     */
    private void receiveFromAliceAsBob(RemoteHostId from, UDPPacketReader.PeerTestReader testInfo, long nonce, PeerTestState state) {
        // we are Bob, so pick a (potentially) Charlie and send Charlie Alice's info
        PeerState charlie;
        RouterInfo charlieInfo = null;
        if (state == null) { // pick a new charlie
            charlie = _transport.pickTestPeer(from);
        } else {
            charlie = _transport.getPeerState(new RemoteHostId(state.getCharlieIP().getAddress(), state.getCharliePort()));
        }
        if (charlie != null)
            charlieInfo = _context.netDb().lookupRouterInfoLocally(charlie.getRemotePeer());
        
        if ( (charlie == null) || (charlieInfo == null) ) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unable to pick a charlie");
            return;
        }
        
        // TODO should only do most of this if isNew
        InetAddress aliceIP = null;
        SessionKey aliceIntroKey = null;
        try {
            aliceIP = InetAddress.getByAddress(from.getIP());
            aliceIntroKey = new SessionKey(new byte[SessionKey.KEYSIZE_BYTES]);
            testInfo.readIntroKey(aliceIntroKey.getData(), 0);

            RouterAddress raddr = _transport.getTargetAddress(charlieInfo);
            if (raddr == null) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Unable to pick a charlie");
                return;
            }
            UDPAddress addr = new UDPAddress(raddr);
            byte[] ikey = addr.getIntroKey();
            if (ikey == null) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Unable to pick a charlie");
                return;
            }
            SessionKey charlieIntroKey = new SessionKey(ikey);
            
            //UDPPacket packet = _packetBuilder.buildPeerTestToAlice(aliceIP, from.getPort(), aliceIntroKey, charlieIntroKey, nonce);
            //_transport.send(packet);

            long now = _context.clock().now();
            boolean isNew = false;
            if (state == null) {
                isNew = true;
                state = new PeerTestState(BOB, nonce, now);
            } else {
                if (state.getReceiveAliceTime() > now - (RESEND_TIMEOUT / 2)) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Too soon, not retransmitting: " + state);
                    return;
                }
            }
            state.setAliceIP(aliceIP);
            state.setAlicePort(from.getPort());
            state.setAliceIntroKey(aliceIntroKey);
            state.setCharlieIP(charlie.getRemoteIPAddress());
            state.setCharliePort(charlie.getRemotePort());
            state.setCharlieIntroKey(charlieIntroKey);
            state.setLastSendTime(now);
            state.setReceiveAliceTime(now);
            
            if (state.incrementPacketsRelayed() > MAX_RELAYED_PER_TEST_BOB) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Too many, not retransmitting: " + state);
                return;
            }
            
            if (isNew) {
                _activeTests.put(Long.valueOf(nonce), state);
                _context.simpleScheduler().addEvent(new RemoveTest(nonce), MAX_CHARLIE_LIFETIME);
            }
            
            UDPPacket packet = _packetBuilder.buildPeerTestToCharlie(aliceIP, from.getPort(), aliceIntroKey, nonce, 
                                                                     charlie.getRemoteIPAddress(), 
                                                                     charlie.getRemotePort(), 
                                                                     charlie.getCurrentCipherKey(), 
                                                                     charlie.getCurrentMACKey());
            
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Receive from Alice: " + state);
            
            _transport.send(packet);
        } catch (UnknownHostException uhe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unable to build the aliceIP from " + from, uhe);
            _context.statManager().addRateData("udp.testBadIP", 1);
        }
    }
    
    /**
     * The PeerTest message came from one of the Charlies picked for an existing test, so send Alice the
     * packet verifying participation.
     *
     * testInfo IP/port ignored
     * @param state non-null
     */
    private void receiveFromCharlieAsBob(RemoteHostId from, PeerTestState state) {
        long now = _context.clock().now();
        if (state.getReceiveCharlieTime() > now - (RESEND_TIMEOUT / 2)) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Too soon, not retransmitting: " + state);
            return;
        }

        if (state.incrementPacketsRelayed() > MAX_RELAYED_PER_TEST_BOB) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Too many, not retransmitting: " + state);
            return;
        }
        state.setReceiveCharlieTime(now);
        
        UDPPacket packet = _packetBuilder.buildPeerTestToAlice(state.getAliceIP(), state.getAlicePort(),
                                                               state.getAliceIntroKey(), state.getCharlieIntroKey(), 
                                                               state.getNonce());

        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Receive from Charlie, sending Alice back the OK: " + state);

        _transport.send(packet);
    }
    
    /** 
     * We are charlie, so send Alice her PeerTest message  
     *
     * testInfo IP/port ignored
     * @param state non-null
     */
    private void receiveFromAliceAsCharlie(RemoteHostId from, UDPPacketReader.PeerTestReader testInfo,
                                           long nonce, PeerTestState state) {
        long now = _context.clock().now();
        if (state.getReceiveAliceTime() > now - (RESEND_TIMEOUT / 2)) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Too soon, not retransmitting: " + state);
            return;
        }

        if (state.incrementPacketsRelayed() > MAX_RELAYED_PER_TEST_CHARLIE) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Too many, not retransmitting: " + state);
            return;
        }
        state.setReceiveAliceTime(now);

        try {
            InetAddress aliceIP = InetAddress.getByAddress(from.getIP());
            SessionKey aliceIntroKey = new SessionKey(new byte[SessionKey.KEYSIZE_BYTES]);
            testInfo.readIntroKey(aliceIntroKey.getData(), 0);
            UDPPacket packet = _packetBuilder.buildPeerTestToAlice(aliceIP, from.getPort(), aliceIntroKey, _transport.getIntroKey(), nonce);

            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Receive from Alice: " + state);
            
            _transport.send(packet);
        } catch (UnknownHostException uhe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unable to build the aliceIP from " + from, uhe);
            _context.statManager().addRateData("udp.testBadIP", 1);
        }
    }
    
    /** 
     * forget about charlie's nonce after a short while.
     */
    private class RemoveTest implements SimpleTimer.TimedEvent {
        private final long _nonce;

        public RemoveTest(long nonce) {
            _nonce = nonce;
        }

        public void timeReached() {
                _activeTests.remove(Long.valueOf(_nonce));
        }
    }
}
