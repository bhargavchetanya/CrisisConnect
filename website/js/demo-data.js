/* Sample scenario used when the Java server is not running ("demo mode").
   Mirrors com.crisisconnect.db.DataSeeder so the website looks the same either way. */
window.DEMO = (() => {
  const cities = {
    "Patiala": [30.3398, 76.3869], "Chandigarh": [30.7333, 76.7794], "Mohali": [30.7046, 76.7179],
    "Rajpura": [30.4840, 76.5940], "Ambala": [30.3782, 76.7767], "Sangrur": [30.2458, 75.8421],
    "Ludhiana": [30.9010, 75.8573], "Jalandhar": [31.3260, 75.5762], "Amritsar": [31.6340, 74.8723],
    "Bathinda": [30.2110, 74.9455], "Samana": [30.1500, 76.1900], "Shimla": [31.1048, 77.1734],
    "New Delhi": [28.6139, 77.2090]
  };
  const day = 86400000, hour = 3600000, now = Date.now();
  const iso = t => new Date(t).toISOString().slice(0, 19);

  const orgs = [
    [1, "NDRF 7th Battalion", "GOVERNMENT", "Patiala"], [2, "Indian Red Cross - Patiala", "NGO", "Patiala"],
    [3, "PGIMER Chandigarh", "HOSPITAL", "Chandigarh"], [4, "Rajindra Hospital", "HOSPITAL", "Patiala"],
    [5, "Khalsa Aid Langar Seva", "COMMUNITY", "Ludhiana"], [6, "Thapar NSS Volunteers", "COMMUNITY", "Patiala"],
    [7, "PRTC Fleet Services", "GOVERNMENT", "Rajpura"], [8, "Civil Defence Ambala", "GOVERNMENT", "Ambala"]
  ].map(([id, name, type, city]) => ({ id, name, type, city }));
  const orgName = id => orgs.find(o => o.id === id).name;

  const res = (id, org, type, name, qty, city, expDays) => ({
    id, name, type, available: qty, capacity: qty, organization: orgName(org), city,
    lat: cities[city][0], lon: cities[city][1],
    expiry: expDays == null ? null : new Date(now + expDays * day).toISOString().slice(0, 10)
  });
  const resources = [
    res(1, 5, "FOOD", "Langar meal kits", 500, "Ludhiana", 2),
    res(2, 2, "FOOD", "Dry ration packets", 450, "Patiala", 30),
    res(3, 2, "FOOD", "Drinking water (20L cans)", 300, "Rajpura", null),
    res(4, 3, "MEDICAL", "O-ve blood units", 40, "Chandigarh", 20),
    res(5, 4, "MEDICAL", "Trauma & first-aid kits", 150, "Patiala", 180),
    res(6, 3, "MEDICAL", "Oxygen cylinders", 60, "Chandigarh", null),
    res(7, 4, "MEDICAL", "Anti-venom & ORS stock", 90, "Patiala", 5),
    res(8, 2, "SHELTER", "Govt. school relief camp", 260, "Patiala", null),
    res(9, 8, "SHELTER", "Community hall beds", 120, "Ambala", null),
    res(10, 5, "SHELTER", "Gurudwara sarai beds", 180, "Ludhiana", null),
    res(11, 1, "VEHICLE", "Inflatable rescue boats", 10, "Patiala", null),
    res(12, 7, "VEHICLE", "Evacuation buses", 10, "Rajpura", null),
    res(13, 4, "VEHICLE", "108 Ambulances", 6, "Patiala", null),
    res(14, 6, "VOLUNTEER", "NSS trained volunteers", 70, "Patiala", null),
    res(15, 8, "VOLUNTEER", "Civil defence wardens", 45, "Ambala", null)
  ];

  const req = (id, title, d, t, qty, people, u, city, vul, hoursAgo) => ({
    id, title, disasterType: d, resourceType: t, quantityNeeded: qty, quantityFulfilled: 0,
    peopleAffected: people, urgency: u, city, lat: cities[city][0], lon: cities[city][1],
    status: "OPEN", vulnerable: vul, source: "DESKTOP", createdAt: iso(now - hoursAgo * hour)
  });
  const requests = [
    req(1, "Ghaggar overflow - villages cut off", "FLOOD", "VEHICLE", 8, 1500, "CRITICAL", "Samana", true, 5),
    req(2, "Flood relief camp needs food", "FLOOD", "FOOD", 900, 900, "HIGH", "Samana", true, 4),
    req(3, "NH-44 multi-vehicle pile-up", "ACCIDENT", "MEDICAL", 45, 22, "CRITICAL", "Rajpura", false, 1),
    req(4, "Ambulances for NH-44 casualties", "ACCIDENT", "VEHICLE", 6, 22, "CRITICAL", "Rajpura", false, 1),
    req(5, "Displaced families need shelter", "FLOOD", "SHELTER", 650, 650, "HIGH", "Patiala", true, 6),
    req(6, "Dengue spike - ORS & medicines", "MEDICAL_SHORTAGE", "MEDICAL", 160, 300, "MEDIUM", "Sangrur", false, 10),
    req(7, "Sandbagging embankment crew", "FLOOD", "VOLUNTEER", 60, 5000, "HIGH", "Samana", false, 3),
    req(8, "Drinking water for low-lying colony", "FLOOD", "FOOD", 250, 600, "MEDIUM", "Patiala", false, 8),
    req(9, "Langar for stranded truckers", "FLOOD", "FOOD", 450, 450, "MEDIUM", "Sangrur", false, 7),
    req(10, "Evacuate stranded families - Ghanaur", "FLOOD", "VEHICLE", 5, 800, "HIGH", "Rajpura", true, 2),
    req(11, "Heatwave cooling centre volunteers", "HEATWAVE", "VOLUNTEER", 20, 200, "LOW", "Bathinda", false, 12),
    req(12, "Factory fire - burn care supplies", "FIRE", "MEDICAL", 25, 40, "HIGH", "Ludhiana", false, 2)
  ];

  return {
    cities, orgs, resources, requests, allocations: [],
    events: [{ type: "INFO", message: "Demo mode - start the Java app for live data", time: iso(now) }],
    meta: {
      cities: Object.keys(cities),
      disasterTypes: ["FLOOD", "ACCIDENT", "MEDICAL_SHORTAGE", "FIRE", "EARTHQUAKE", "HEATWAVE", "OTHER"],
      resourceTypes: ["FOOD", "MEDICAL", "SHELTER", "VEHICLE", "VOLUNTEER"],
      urgencyLevels: ["CRITICAL", "HIGH", "MEDIUM", "LOW"]
    }
  };
})();
