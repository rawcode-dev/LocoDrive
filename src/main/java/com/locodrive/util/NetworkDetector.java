package com.locodrive.util;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * Detects available LAN IPv4 addresses on the machine.
 * Filters out loopback, virtual, and link-local addresses.
 */
public class NetworkDetector {

    /**
     * Returns a list of plausible LAN IP addresses, e.g. ["192.168.1.10", "10.0.0.5"].
     * Never returns loopback (127.x) or link-local (169.254.x) addresses.
     */
    public static List<String> getLanAddresses() {
        List<String> addresses = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();

                // Skip loopback, down, and virtual interfaces
                if (ni.isLoopback() || !ni.isUp() || ni.isVirtual()) continue;

                // Skip common virtual adapter names
                String name = ni.getName().toLowerCase();
                if (name.startsWith("vmnet") || name.startsWith("vbox")
                    || name.startsWith("docker") || name.startsWith("virbr")) continue;

                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (!(addr instanceof Inet4Address)) continue;       // IPv4 only
                    if (addr.isLoopbackAddress()) continue;              // Skip 127.x
                    if (addr.isLinkLocalAddress()) continue;             // Skip 169.254.x
                    addresses.add(addr.getHostAddress());
                }
            }
        } catch (Exception e) {
            System.err.println("Error detecting network interfaces: " + e.getMessage());
        }

        // Fall back to localhost if nothing found
        if (addresses.isEmpty()) {
            addresses.add("127.0.0.1");
        }
        return addresses;
    }

    /**
     * Returns the "best" single LAN IP — prefers 192.168.x.x, then 10.x.x.x.
     */
    public static String getBestAddress() {
        List<String> all = getLanAddresses();
        for (String ip : all) if (ip.startsWith("192.168.")) return ip;
        for (String ip : all) if (ip.startsWith("10."))       return ip;
        for (String ip : all) if (ip.startsWith("172."))      return ip;
        return all.isEmpty() ? "127.0.0.1" : all.get(0);
    }

    /**
     * Checks if a port is currently available (not in use).
     */
    public static boolean isPortAvailable(int port) {
        try (java.net.ServerSocket ss = new java.net.ServerSocket(port)) {
            ss.setReuseAddress(true);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
