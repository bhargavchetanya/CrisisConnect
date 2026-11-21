package com.crisisconnect.api;

/** Returned by a route handler to answer with "302 Found" instead of JSON (used by the Google sign-in flow). */
record Redirect(String location) { }
