package com.crisisconnect.model;

import java.util.Objects;

/** An organization that donates / owns resources (NGO, hospital, govt. body ...). */
public class Organization extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private String name;
    private OrganizationType type;
    private String contactPhone;
    private String email;
    private GeoLocation location;

    public Organization() { }

    public Organization(String name, OrganizationType type, String contactPhone,
                        String email, GeoLocation location) {
        this.name = Objects.requireNonNull(name);
        this.type = Objects.requireNonNull(type);
        this.contactPhone = contactPhone;
        this.email = email;
        this.location = Objects.requireNonNull(location);
    }

    @Override
    public String displayName() { return name + " (" + type + ")"; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public OrganizationType getType() { return type; }
    public void setType(OrganizationType type) { this.type = type; }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public GeoLocation getLocation() { return location; }
    public void setLocation(GeoLocation location) { this.location = location; }
    public String getCity() { return location == null ? "" : location.getCity(); }

    @Override
    public String toString() { return name; } // used by JavaFX ComboBox
}
