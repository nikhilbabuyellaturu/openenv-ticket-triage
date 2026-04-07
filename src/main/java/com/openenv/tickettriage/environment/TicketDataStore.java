package com.openenv.tickettriage.environment;

import com.openenv.tickettriage.model.SupportTicket;
import com.openenv.tickettriage.model.SupportTicket.Category;
import com.openenv.tickettriage.model.SupportTicket.Priority;
import com.openenv.tickettriage.model.SupportTicket.Team;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * In-memory store of realistic IT support tickets.
 * Ground truth labels are embedded but hidden from agents during observation.
 */
@Component
public class TicketDataStore {

    private final Map<String, SupportTicket> ticketMap = new LinkedHashMap<>();

    @PostConstruct
    public void init() {
        List<SupportTicket> tickets = buildTickets();
        tickets.forEach(t -> ticketMap.put(t.getTicketId(), t));
    }

    public Optional<SupportTicket> findById(String ticketId) {
        return Optional.ofNullable(ticketMap.get(ticketId));
    }

    public List<SupportTicket> findAll() {
        return new ArrayList<>(ticketMap.values());
    }

    public List<SupportTicket> findSimilar(String ticketId, int limit) {
        SupportTicket target = ticketMap.get(ticketId);
        if (target == null) return List.of();
        return ticketMap.values().stream()
                .filter(t -> !t.getTicketId().equals(ticketId))
                .filter(t -> t.getGroundTruthCategory() == target.getGroundTruthCategory())
                .limit(limit)
                .collect(Collectors.toList());
    }

    public SupportTicket getRandomTicket(Random rng) {
        List<SupportTicket> all = findAll();
        return all.get(rng.nextInt(all.size()));
    }

    // ─────────────────────────────────────────────────────────────
    // Ticket Catalogue — 15 realistic IT support tickets
    // ─────────────────────────────────────────────────────────────
    private List<SupportTicket> buildTickets() {
        return List.of(

            // ── HARDWARE ──
            SupportTicket.builder()
                .ticketId("TKT-001")
                .title("Laptop screen flickering and going black")
                .description("My laptop screen has been flickering since this morning and sometimes goes completely black for 5-10 seconds. I've tried restarting but the problem persists. I have a critical client presentation at 2 PM today and cannot work properly. The laptop is a Dell Latitude 5520, purchased 18 months ago.")
                .userName("Priya Sharma").userDepartment("Sales").submittedAt("2025-03-15T09:15:00")
                .groundTruthPriority(Priority.HIGH)
                .groundTruthCategory(Category.HARDWARE)
                .groundTruthTeam(Team.HELPDESK)
                .groundTruthResolutionHint("Check display driver first, then inspect physical cable connection. Replace if hardware fault confirmed.")
                .keywords(List.of("screen", "flickering", "laptop", "display", "presentation"))
                .build(),

            SupportTicket.builder()
                .ticketId("TKT-002")
                .title("Office printer not responding to print jobs")
                .description("The shared HP LaserJet printer on the 3rd floor (Finance dept) has stopped accepting print jobs since yesterday afternoon. Print queue shows jobs stuck in 'Sending' state. Several Finance staff are affected and cannot print invoices.")
                .userName("Raj Patel").userDepartment("Finance").submittedAt("2025-03-15T10:00:00")
                .groundTruthPriority(Priority.MEDIUM)
                .groundTruthCategory(Category.HARDWARE)
                .groundTruthTeam(Team.HELPDESK)
                .groundTruthResolutionHint("Clear print queue via Windows print spooler service, restart printer, reinstall driver if needed.")
                .keywords(List.of("printer", "print queue", "HP", "LaserJet", "stuck"))
                .build(),

            SupportTicket.builder()
                .ticketId("TKT-003")
                .title("Mouse and keyboard not working on workstation")
                .description("Both my mouse and keyboard have stopped working after I came back from lunch. They are USB-connected. The computer is on. I have tried unplugging and replugging. I cannot work at all.")
                .userName("Sunita Das").userDepartment("Accounts").submittedAt("2025-03-14T13:45:00")
                .groundTruthPriority(Priority.MEDIUM)
                .groundTruthCategory(Category.HARDWARE)
                .groundTruthTeam(Team.HELPDESK)
                .groundTruthResolutionHint("Try different USB ports, check Device Manager for driver issues. USB hub may have failed.")
                .keywords(List.of("mouse", "keyboard", "USB", "workstation"))
                .build(),

            // ── SOFTWARE ──
            SupportTicket.builder()
                .ticketId("TKT-004")
                .title("SAP ERP application crashes on startup")
                .description("SAP ERP client crashes immediately after login screen on all Finance workstations since the overnight Windows update. Error message: 'SAP GUI runtime exception - memory allocation failed'. Finance team cannot process end-of-month payroll due tomorrow. Affects 12 users.")
                .userName("Anand Kumar").userDepartment("Finance").submittedAt("2025-03-15T08:30:00")
                .groundTruthPriority(Priority.CRITICAL)
                .groundTruthCategory(Category.SOFTWARE)
                .groundTruthTeam(Team.SYSADMIN)
                .groundTruthResolutionHint("Roll back KB5023698 Windows update. Reinstall SAP GUI 7.60 patch 11. Coordinate with SAP Basis team.")
                .keywords(List.of("SAP", "ERP", "crash", "Windows update", "payroll", "Finance"))
                .build(),

            SupportTicket.builder()
                .ticketId("TKT-005")
                .title("Microsoft Teams calls dropping after 2 minutes")
                .description("Teams video calls keep disconnecting after approximately 2 minutes. This started happening yesterday. Audio-only calls also drop. My internet connection is fine — I can browse and use other apps normally. Running Teams version 1.6.00.")
                .userName("Meera Iyer").userDepartment("HR").submittedAt("2025-03-14T16:00:00")
                .groundTruthPriority(Priority.MEDIUM)
                .groundTruthCategory(Category.SOFTWARE)
                .groundTruthTeam(Team.HELPDESK)
                .groundTruthResolutionHint("Clear Teams cache, update to latest version. Check QoS policy settings. May need network team involvement if widespread.")
                .keywords(List.of("Teams", "video call", "dropping", "disconnect", "Microsoft"))
                .build(),

            SupportTicket.builder()
                .ticketId("TKT-006")
                .title("Excel macro not running - security warning")
                .description("When I try to run the monthly report macro in Excel, I get a security warning and the macro is blocked. I need to run this macro every month for board reports. This worked fine last month.")
                .userName("Kavita Rao").userDepartment("Strategy").submittedAt("2025-03-13T11:00:00")
                .groundTruthPriority(Priority.LOW)
                .groundTruthCategory(Category.SOFTWARE)
                .groundTruthTeam(Team.HELPDESK)
                .groundTruthResolutionHint("Enable macros in Trust Center settings or add file location to trusted locations list.")
                .keywords(List.of("Excel", "macro", "security", "blocked"))
                .build(),

            // ── NETWORK ──
            SupportTicket.builder()
                .ticketId("TKT-007")
                .title("Entire building has no internet access")
                .description("All staff in Building B (approximately 200 employees) have lost internet connectivity as of 7:45 AM. Internal network and file servers are accessible. The ISP link appears to be down. All customer-facing web services are unreachable. Business operations are severely impacted.")
                .userName("IT Duty Manager").userDepartment("IT Operations").submittedAt("2025-03-15T07:50:00")
                .groundTruthPriority(Priority.CRITICAL)
                .groundTruthCategory(Category.NETWORK)
                .groundTruthTeam(Team.NETWORK_OPS)
                .groundTruthResolutionHint("Contact ISP immediately. Activate 4G failover link. Escalate to NOC. Prepare incident communication to management.")
                .keywords(List.of("internet", "outage", "building", "ISP", "connectivity", "network"))
                .build(),

            SupportTicket.builder()
                .ticketId("TKT-008")
                .title("VPN connection timing out from home")
                .description("I have been unable to connect to the corporate VPN from home since yesterday evening. I get 'Connection timeout' error after 30 seconds. My colleague in the same area can connect fine. I am using Cisco AnyConnect.")
                .userName("Vikram Singh").userDepartment("Development").submittedAt("2025-03-14T09:00:00")
                .groundTruthPriority(Priority.MEDIUM)
                .groundTruthCategory(Category.NETWORK)
                .groundTruthTeam(Team.NETWORK_OPS)
                .groundTruthResolutionHint("Check VPN profile configuration. Reset user session on VPN concentrator. Verify split tunneling settings.")
                .keywords(List.of("VPN", "Cisco", "AnyConnect", "timeout", "remote", "home"))
                .build(),

            SupportTicket.builder()
                .ticketId("TKT-009")
                .title("Slow file transfer on internal network")
                .description("Transferring files from my workstation to the shared file server is extremely slow — about 2 MB/s instead of the usual 100+ MB/s. This has been happening for 3 days. Other colleagues report the same issue on this floor.")
                .userName("Deepak Nair").userDepartment("Engineering").submittedAt("2025-03-12T14:00:00")
                .groundTruthPriority(Priority.MEDIUM)
                .groundTruthCategory(Category.NETWORK)
                .groundTruthTeam(Team.NETWORK_OPS)
                .groundTruthResolutionHint("Check switch port configuration, look for duplex mismatch. Check for network loop or high collision rates on the floor switch.")
                .keywords(List.of("slow", "file transfer", "network", "speed", "server"))
                .build(),

            // ── SECURITY ──
            SupportTicket.builder()
                .ticketId("TKT-010")
                .title("Phishing email received — employee clicked link")
                .description("An employee in the Finance department received a phishing email impersonating our CEO asking for an urgent wire transfer. The employee clicked the link and entered their corporate email credentials before realizing it was fraudulent. Potential credential compromise and data breach risk.")
                .userName("Ramesh Krishnan").userDepartment("Finance").submittedAt("2025-03-15T10:30:00")
                .groundTruthPriority(Priority.CRITICAL)
                .groundTruthCategory(Category.SECURITY)
                .groundTruthTeam(Team.SECURITY_OPS)
                .groundTruthResolutionHint("Immediately reset user credentials. Revoke all active sessions. Check for unauthorized access in audit logs. File security incident report. Notify CISO.")
                .keywords(List.of("phishing", "credential", "compromise", "email", "security", "breach"))
                .build(),

            SupportTicket.builder()
                .ticketId("TKT-011")
                .title("Suspicious login attempts on server")
                .description("Security monitoring has detected 500+ failed SSH login attempts on our production web server from IP 192.168.45.x over the last hour. No successful logins yet. Possible brute force attack.")
                .userName("Security Monitoring System").userDepartment("IT Security").submittedAt("2025-03-15T02:15:00")
                .groundTruthPriority(Priority.HIGH)
                .groundTruthCategory(Category.SECURITY)
                .groundTruthTeam(Team.SECURITY_OPS)
                .groundTruthResolutionHint("Block source IP range immediately. Enable fail2ban. Rotate SSH keys. Review firewall rules. Notify DevOps.")
                .keywords(List.of("SSH", "brute force", "login attempts", "server", "security"))
                .build(),

            SupportTicket.builder()
                .ticketId("TKT-012")
                .title("USB drive found in car park — should I plug it in?")
                .description("I found a USB drive in the company car park with a label saying 'Salary Information Q1'. I wanted to check if it belongs to anyone before handing it in. Is it safe to plug in to my work computer?")
                .userName("Pooja Menon").userDepartment("Logistics").submittedAt("2025-03-13T08:45:00")
                .groundTruthPriority(Priority.HIGH)
                .groundTruthCategory(Category.SECURITY)
                .groundTruthTeam(Team.SECURITY_OPS)
                .groundTruthResolutionHint("Do NOT plug in. This is a classic baiting attack. Collect device, hand to Security team for forensic analysis. Educate employee on USB security policy.")
                .keywords(List.of("USB", "baiting", "unknown device", "security policy"))
                .build(),

            // ── ACCESS ──
            SupportTicket.builder()
                .ticketId("TKT-013")
                .title("New joiner cannot access any internal systems")
                .description("New employee Arjun Mehta (Employee ID EMP4521) joined the company yesterday but has no access to email, file shares, or any internal applications. His onboarding was scheduled for today. Manager is Lakshmi Bhat.")
                .userName("Lakshmi Bhat").userDepartment("Operations").submittedAt("2025-03-15T09:00:00")
                .groundTruthPriority(Priority.HIGH)
                .groundTruthCategory(Category.ACCESS)
                .groundTruthTeam(Team.HELPDESK)
                .groundTruthResolutionHint("Verify employee exists in AD. Run new user provisioning script. Assign role-based access groups per department. Set temporary password.")
                .keywords(List.of("new joiner", "access", "onboarding", "Active Directory", "provisioning"))
                .build(),

            SupportTicket.builder()
                .ticketId("TKT-014")
                .title("Account locked out after password change")
                .description("My account got locked after I changed my password this morning. I can no longer log in to Windows or any applications. I need access urgently for a board presentation at 11 AM.")
                .userName("Sanjay Gupta").userDepartment("Finance").submittedAt("2025-03-15T10:45:00")
                .groundTruthPriority(Priority.HIGH)
                .groundTruthCategory(Category.ACCESS)
                .groundTruthTeam(Team.HELPDESK)
                .groundTruthResolutionHint("Unlock AD account, clear cached credentials on workstation. Ensure new password meets complexity policy.")
                .keywords(List.of("lockout", "password", "account", "Windows", "locked"))
                .build(),

            SupportTicket.builder()
                .ticketId("TKT-015")
                .title("Need read access to Finance shared folder")
                .description("I have been assigned to assist the Finance team for a project and need read-only access to the Finance shared folder on the file server. My manager Prem Chandran has verbally approved this.")
                .userName("Aditi Joshi").userDepartment("Strategy").submittedAt("2025-03-13T15:00:00")
                .groundTruthPriority(Priority.LOW)
                .groundTruthCategory(Category.ACCESS)
                .groundTruthTeam(Team.HELPDESK)
                .groundTruthResolutionHint("Obtain written approval from manager and Finance head. Grant read-only ACL on specific folder after approval. Log access grant.")
                .keywords(List.of("access request", "shared folder", "Finance", "read-only", "permission"))
                .build()
        );
    }
}
