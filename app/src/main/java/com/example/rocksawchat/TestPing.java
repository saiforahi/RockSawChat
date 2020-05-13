package com.example.rocksawchat;

import android.os.AsyncTask;

import org.savarese.vserv.tcpip.ICMPEchoPacket;
import org.savarese.vserv.tcpip.OctetConverter;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class TestPing extends AsyncTask<Void,Void, Ping.Pinger> {
    private Exception exception;
    private ScheduledThreadPoolExecutor executor=null;
    private int count;
    private InetAddress address;

    protected Ping.Pinger doInBackground(Void... voids) {
        System.out.println("working");
        executor = new ScheduledThreadPoolExecutor(2);
        Ping.Pinger ping = null;
        try {
            address = InetAddress.getByName("8.8.8.8");
            final String hostname = address.getCanonicalHostName();
            final String hostaddr = address.getHostAddress();

            count = 5;
            // Ping programs usually use the process ID for the identifier,
            // but we can't get it and this is only a demo.
            final int id = 65535;
            if (address instanceof Inet6Address){
                System.out.println("wtf");
                ping = new Ping.PingerIPv6(id);
            }
            else{
                System.out.println("wtfno");
                ping = new Ping.Pinger(id);
            }
            ping.setEchoReplyListener(new Ping.EchoReplyListener() {
                StringBuffer buffer = new StringBuffer(128);
                public void notifyEchoReply(ICMPEchoPacket packet, byte[] data, int dataOffset, byte[] srcAddress) throws IOException {
                    long end = System.nanoTime();
                    long start = OctetConverter.octetsToLong(data, dataOffset);
                    // Note: Java and JNI overhead will be noticeable (100-200
                    // microseconds) for sub-millisecond transmission times.
                    // The first ping may even show several seconds of delay
                    // because of initial JIT compilation overhead.
                    double rtt = (double) (end - start) / 1e6;

                    buffer.setLength(0);
                    buffer.append(packet.getICMPPacketByteLength())
                            .append(" bytes from ").append(hostname).append(" (");
                    buffer.append(InetAddress.getByAddress(srcAddress).toString());
                    buffer.append("): icmp_seq=")
                            .append(packet.getSequenceNumber())
                            .append(" ttl=").append(packet.getTTL()).append(" time=")
                            .append(rtt).append(" ms");
                    System.out.println(buffer.toString());
                }
            });
            System.out.println("PING " + hostname + " (" + hostaddr + ") " + ping.getRequestDataLength() + "(" + ping.getRequestPacketLength() + ") bytes of data).");
        } catch (Exception e) {
            executor.shutdown();
            e.printStackTrace();
        }
        return ping;
    }

    protected void onPostExecute(final Ping.Pinger ping) {
        // TODO: check this.exception
        // TODO: do something with the feed
        final CountDownLatch latch = new CountDownLatch(1);

        executor.scheduleAtFixedRate(new Runnable() {
            int counter = count;

            public void run() {
                try {
                    if (counter > 0) {
                        ping.sendEchoRequest(address);
                        if (counter == count)
                            latch.countDown();
                        --counter;
                    } else
                        executor.shutdown();
                } catch (IOException ioe) {
                    ioe.printStackTrace();
                }
            }
        }, 0, 1, TimeUnit.SECONDS);

        // We wait for first ping to be sent because Windows times out
        // with WSAETIMEDOUT if echo request hasn't been sent first.
        // POSIX does the right thing and just blocks on the first receive.
        // An alternative is to bind the socket first, which should allow a
        // receive to be performed frst on Windows.
        try {
            latch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        for (int i = 0; i < count; ++i) {
            try {
                ping.receiveEchoReply();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        try {
            ping.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

