package com.crisisconnect.db;

import com.crisisconnect.dao.DaoFactory;
import com.crisisconnect.model.DisasterType;
import com.crisisconnect.model.EmergencyRequest;
import com.crisisconnect.model.Organization;
import com.crisisconnect.model.OrganizationType;
import com.crisisconnect.model.Resource;
import com.crisisconnect.model.ResourceFactory;
import com.crisisconnect.model.ResourceType;
import com.crisisconnect.model.UrgencyLevel;
import com.crisisconnect.util.CityDirectory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Loads a realistic demo scenario (Punjab monsoon floods + NH-44 accident)
 * the first time the application starts with an empty database.
 */
public final class DataSeeder {

    private static final Logger LOG = Logger.getLogger(DataSeeder.class.getName());

    private DataSeeder() { }

    public static void seedIfEmpty() {
        if (DaoFactory.organizations().count() > 0) return;
        LOG.info("Empty database - loading demo scenario");

        Map<String, Integer> org = new HashMap<>();
        org.put("NDRF", saveOrg("NDRF 7th Battalion", OrganizationType.GOVERNMENT, "Patiala", "+91 172 2700011", "ndrf7@gov.in"));
        org.put("REDCROSS", saveOrg("Indian Red Cross - Patiala", OrganizationType.NGO, "Patiala", "+91 175 2212345", "patiala@redcross.org"));
        org.put("PGI", saveOrg("PGIMER Chandigarh", OrganizationType.HOSPITAL, "Chandigarh", "+91 172 2746018", "emergency@pgimer.edu.in"));
        org.put("RAJINDRA", saveOrg("Rajindra Hospital", OrganizationType.HOSPITAL, "Patiala", "+91 175 2212018", "rh@punjab.gov.in"));
        org.put("LANGAR", saveOrg("Khalsa Aid Langar Seva", OrganizationType.COMMUNITY, "Ludhiana", "+91 98140 11111", "seva@khalsaaid.org"));
        org.put("NSS", saveOrg("Thapar NSS Volunteers", OrganizationType.COMMUNITY, "Patiala", "+91 175 2393021", "nss@thapar.edu"));
        org.put("PRTC", saveOrg("PRTC Fleet Services", OrganizationType.GOVERNMENT, "Rajpura", "+91 175 2365555", "fleet@prtc.in"));
        org.put("CIVIL", saveOrg("Civil Defence Ambala", OrganizationType.GOVERNMENT, "Ambala", "+91 171 2600100", "cd.ambala@hry.gov.in"));

        saveRes(org.get("LANGAR"), ResourceType.FOOD, "Langar meal kits", 500, "Ludhiana", 2);
        saveRes(org.get("REDCROSS"), ResourceType.FOOD, "Dry ration packets", 450, "Patiala", 30);
        saveRes(org.get("REDCROSS"), ResourceType.FOOD, "Drinking water (20L cans)", 300, "Rajpura", null);
        saveRes(org.get("PGI"), ResourceType.MEDICAL, "O-ve blood units", 40, "Chandigarh", 20);
        saveRes(org.get("RAJINDRA"), ResourceType.MEDICAL, "Trauma & first-aid kits", 150, "Patiala", 180);
        saveRes(org.get("PGI"), ResourceType.MEDICAL, "Oxygen cylinders", 60, "Chandigarh", null);
        saveRes(org.get("RAJINDRA"), ResourceType.MEDICAL, "Anti-venom & ORS stock", 90, "Patiala", 5);
        saveRes(org.get("REDCROSS"), ResourceType.SHELTER, "Govt. school relief camp", 260, "Patiala", null);
        saveRes(org.get("CIVIL"), ResourceType.SHELTER, "Community hall beds", 120, "Ambala", null);
        saveRes(org.get("LANGAR"), ResourceType.SHELTER, "Gurudwara sarai beds", 180, "Ludhiana", null);
        saveRes(org.get("NDRF"), ResourceType.VEHICLE, "Inflatable rescue boats", 10, "Patiala", null);
        saveRes(org.get("PRTC"), ResourceType.VEHICLE, "Evacuation buses", 10, "Rajpura", null);
        saveRes(org.get("RAJINDRA"), ResourceType.VEHICLE, "108 Ambulances", 6, "Patiala", null);
        saveRes(org.get("NSS"), ResourceType.VOLUNTEER, "NSS trained volunteers", 70, "Patiala", null);
        saveRes(org.get("CIVIL"), ResourceType.VOLUNTEER, "Civil defence wardens", 45, "Ambala", null);

        saveReq("Ghaggar overflow - villages cut off", DisasterType.FLOOD, ResourceType.VEHICLE, 8, 1500, UrgencyLevel.CRITICAL, "Samana", true, 5);
        saveReq("Flood relief camp needs food", DisasterType.FLOOD, ResourceType.FOOD, 900, 900, UrgencyLevel.HIGH, "Samana", true, 4);
        saveReq("NH-44 multi-vehicle pile-up", DisasterType.ACCIDENT, ResourceType.MEDICAL, 45, 22, UrgencyLevel.CRITICAL, "Rajpura", false, 1);
        saveReq("Ambulances for NH-44 casualties", DisasterType.ACCIDENT, ResourceType.VEHICLE, 6, 22, UrgencyLevel.CRITICAL, "Rajpura", false, 1);
        saveReq("Displaced families need shelter", DisasterType.FLOOD, ResourceType.SHELTER, 650, 650, UrgencyLevel.HIGH, "Patiala", true, 6);
        saveReq("Dengue spike - ORS & medicines", DisasterType.MEDICAL_SHORTAGE, ResourceType.MEDICAL, 160, 300, UrgencyLevel.MEDIUM, "Sangrur", false, 10);
        saveReq("Sandbagging embankment crew", DisasterType.FLOOD, ResourceType.VOLUNTEER, 60, 5000, UrgencyLevel.HIGH, "Samana", false, 3);
        saveReq("Drinking water for low-lying colony", DisasterType.FLOOD, ResourceType.FOOD, 250, 600, UrgencyLevel.MEDIUM, "Patiala", false, 8);
        saveReq("Langar for stranded truckers", DisasterType.FLOOD, ResourceType.FOOD, 450, 450, UrgencyLevel.MEDIUM, "Sangrur", false, 7);
        saveReq("Evacuate stranded families - Ghanaur", DisasterType.FLOOD, ResourceType.VEHICLE, 5, 800, UrgencyLevel.HIGH, "Rajpura", true, 2);
        saveReq("Heatwave cooling centre volunteers", DisasterType.HEATWAVE, ResourceType.VOLUNTEER, 20, 200, UrgencyLevel.LOW, "Bathinda", false, 12);
        saveReq("Factory fire - burn care supplies", DisasterType.FIRE, ResourceType.MEDICAL, 25, 40, UrgencyLevel.HIGH, "Ludhiana", false, 2);
        LOG.info("Demo scenario loaded");
    }

    private static int saveOrg(String name, OrganizationType type, String city, String phone, String email) {
        return DaoFactory.organizations()
                .save(new Organization(name, type, phone, email, CityDirectory.require(city))).getId();
    }

    private static void saveRes(int orgId, ResourceType type, String name, int qty, String city, Integer expiryDays) {
        Resource r = ResourceFactory.create(type, orgId, name, qty, CityDirectory.require(city));
        if (expiryDays != null) r.setExpiryDate(LocalDate.now().plusDays(expiryDays));
        DaoFactory.resources().save(r);
    }

    private static void saveReq(String title, DisasterType d, ResourceType t, int qty, int people,
                                UrgencyLevel u, String city, boolean vulnerable, int hoursAgo) {
        EmergencyRequest r = new EmergencyRequest(title, d, t, qty, people, u, CityDirectory.require(city));
        r.setVulnerableGroups(vulnerable);
        r.setContactPhone("+91 98" + (10000000 + Math.abs(title.hashCode() % 89999999)));
        r.setCreatedAt(LocalDateTime.now().minusHours(hoursAgo));
        DaoFactory.requests().save(r);
    }
}
