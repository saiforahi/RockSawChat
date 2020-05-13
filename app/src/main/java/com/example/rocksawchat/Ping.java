package com.example.rocksawchat;

import com.savarese.rocksaw.net.RawSocket;
import static com.savarese.rocksaw.net.RawSocket.PF_INET;
import static com.savarese.rocksaw.net.RawSocket.PF_INET6;
import static com.savarese.rocksaw.net.RawSocket.getProtocolByName;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Inet6Address;
import java.util.concurrent.*;

import org.savarese.vserv.tcpip.*;
import static org.savarese.vserv.tcpip.ICMPPacket.OFFSET_ICMP_CHECKSUM;

/**
 * <p>The Ping class is a simple demo showing how you can send
 * ICMP echo requests and receive echo replies using raw sockets.
 * It has been updated to work with both IPv4 and IPv6.</p>
 *
 * <p>Note, this is not a model of good programming.  The point
 * of the example is to show how the RawSocket API calls work.  There
 * is much kluginess surrounding the actual packet and protocol
 * handling, all of which is outside of the scope of what RockSaw
 * does.</p>
 *
 * @author <a href="http://www.savarese.org/">Daniel F. Savarese</a>
 */

public class Ping {

    public static interface EchoReplyListener {
        public void notifyEchoReply(ICMPEchoPacket packet, byte[] data, int dataOffset, byte[] srcAddress)
                throws IOException;
    }

    public static class Pinger {
        private static final int TIMEOUT = 10000;
        protected RawSocket socket;
        protected ICMPEchoPacket sendPacket, recvPacket;
        protected int offset, length, dataOffset;
        protected int requestType, replyType;
        protected byte[] sendData, recvData, srcAddress;
        protected int sequence, identifier;
        protected EchoReplyListener listener;

        protected Pinger(int id, int protocolFamily, int protocol) throws IOException
        {
            System.out.println("pinger with param id");
            sequence   = 0;
            identifier = id;
            setEchoReplyListener(null);

            sendPacket = new ICMPEchoPacket(1);
            recvPacket = new ICMPEchoPacket(1);
            sendData = new byte[84];
            recvData = new byte[84];

            sendPacket.setData(sendData);
            recvPacket.setData(recvData);
            sendPacket.setIPHeaderLength(5);
            recvPacket.setIPHeaderLength(5);
            sendPacket.setICMPDataByteLength(56);
            recvPacket.setICMPDataByteLength(56);

            offset     = sendPacket.getIPHeaderByteLength();
            dataOffset = offset + sendPacket.getICMPHeaderByteLength();
            length     = sendPacket.getICMPPacketByteLength();

            socket = new RawSocket();
            socket.open(protocolFamily, protocol);

            try {
                socket.setSendTimeout(TIMEOUT);
                socket.setReceiveTimeout(TIMEOUT);
            } catch(java.net.SocketException se) {
                socket.setUseSelectTimeout(true);
                socket.setSendTimeout(TIMEOUT);
                socket.setReceiveTimeout(TIMEOUT);
            }
        }

        public Pinger(int id) throws IOException {
            this(id, PF_INET, getProtocolByName("icmp"));
            srcAddress  = new byte[4];
            requestType = ICMPPacket.TYPE_ECHO_REQUEST;
            replyType   = ICMPPacket.TYPE_ECHO_REPLY;
        }

        protected void computeSendChecksum(InetAddress host) throws IOException {
            sendPacket.computeICMPChecksum();
        }

        public void setEchoReplyListener(EchoReplyListener l) {
            listener = l;
        }

        /**
         * Closes the raw socket opened by the constructor.  After calling
         * this method, the object cannot be used.
         */
        public void close() throws IOException {
            socket.close();
        }

        public void sendEchoRequest(InetAddress host) throws IOException {
            sendPacket.setType(requestType);
            sendPacket.setCode(0);
            sendPacket.setIdentifier(identifier);
            sendPacket.setSequenceNumber(sequence++);
            OctetConverter.longToOctets(System.nanoTime(), sendData, dataOffset);
            computeSendChecksum(host);
            socket.write(host, sendData, offset, length);
        }

        public void receive() throws IOException {
            socket.read(recvData, srcAddress);
        }

        public void receiveEchoReply() throws IOException {
            do {
                receive();
            } while(recvPacket.getType() != replyType || recvPacket.getIdentifier() != identifier);

            if(listener != null)
                listener.notifyEchoReply(recvPacket, recvData, dataOffset, srcAddress);
        }

        /**
         * Issues a synchronous ping.
         *
         * @param host The host to ping.
         * @return The round trip time in nanoseconds.
         */
        public long ping(InetAddress host) throws IOException {
            sendEchoRequest(host);
            receiveEchoReply();

            long end   = System.nanoTime();
            long start = OctetConverter.octetsToLong(recvData, dataOffset);

            return (end - start);
        }

        /**
         * @return The number of bytes in the data portion of the ICMP ping request
         * packet.
         */
        public int getRequestDataLength() {
            return sendPacket.getICMPDataByteLength();
        }

        /** @return The number of bytes in the entire IP ping request packet. */
        public int getRequestPacketLength() {
            return sendPacket.getIPPacketLength();
        }
    }

    public static class PingerIPv6 extends Pinger {
        private static final int IPPROTO_ICMPV6           = 58;
        private static final int ICMPv6_TYPE_ECHO_REQUEST = 128;
        private static final int ICMPv6_TYPE_ECHO_REPLY   = 129;

        /**
         * Operating system kernels are supposed to calculate the ICMPv6
         * checksum for the sender, but Microsoft's IPv6 stack does not do
         * this.  Nor does it support the IPV6_CHECKSUM socket option.
         * Therefore, in order to work on the Windows family of operating
         * systems, we have to calculate the ICMPv6 checksum.
         */
        private static class ICMPv6ChecksumCalculator extends IPPacket {
            ICMPv6ChecksumCalculator() { super(1); }

            private int computeVirtualHeaderTotal(byte[] destination, byte[] source,
                                                  int icmpLength)
            {
                int total = 0;

                for(int i = 0; i < source.length;)
                    total+=(((source[i++] & 0xff) << 8) | (source[i++] & 0xff));
                for(int i = 0; i < destination.length;)
                    total+=(((destination[i++] & 0xff) << 8) | (destination[i++] & 0xff));

                total+=(icmpLength >>> 16);
                total+=(icmpLength & 0xffff);
                total+=IPPROTO_ICMPV6;

                return total;
            }

            int computeChecksum(byte[] data, ICMPPacket packet, byte[] destination,
                                byte[] source)
            {
                int startOffset    = packet.getIPHeaderByteLength();
                int checksumOffset = startOffset + OFFSET_ICMP_CHECKSUM;
                int ipLength       = packet.getIPPacketLength();
                int icmpLength     = packet.getICMPPacketByteLength();

                setData(data);

                return
                        _computeChecksum_(startOffset, checksumOffset, ipLength,computeVirtualHeaderTotal(destination, source,icmpLength), true);
            }
        }

        private byte[] localAddress;
        private ICMPv6ChecksumCalculator icmpv6Checksummer;

        public PingerIPv6(int id) throws IOException {
            //socket.open(protocolFamily,
            super(id, PF_INET6, IPPROTO_ICMPV6 /*getProtocolByName("ipv6-icmp")*/);
            icmpv6Checksummer = new ICMPv6ChecksumCalculator();
            srcAddress   = new byte[16];
            localAddress = new byte[16];
            requestType  = ICMPv6_TYPE_ECHO_REQUEST;
            replyType    = ICMPv6_TYPE_ECHO_REPLY;
        }

        protected void computeSendChecksum(InetAddress host)
                throws IOException
        {
            // This is necessary only for Windows, which doesn't implement
            // RFC 2463 correctly.
            socket.getSourceAddressForDestination(host, localAddress);
            icmpv6Checksummer.computeChecksum(sendData, sendPacket, host.getAddress(), localAddress);
        }

        public void receive() throws IOException {
            socket.read(recvData, offset, length, srcAddress);
        }
    }
}

