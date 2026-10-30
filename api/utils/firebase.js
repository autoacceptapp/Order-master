/**
 * Firebase Admin Initialization and Common Helpers for Vercel Serverless Functions
 * File: api/utils/firebase.js
 */

const admin = require("firebase-admin");

if (!admin.apps.length) {
  let credential;

  if (process.env.FIREBASE_SERVICE_ACCOUNT) {
    try {
      const serviceAccount = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT);
      credential = admin.credential.cert(serviceAccount);
    } catch (err) {
      console.error("Failed to parse FIREBASE_SERVICE_ACCOUNT JSON:", err);
    }
  }

  if (!credential && process.env.FIREBASE_PROJECT_ID && process.env.FIREBASE_PRIVATE_KEY && process.env.FIREBASE_CLIENT_EMAIL) {
    credential = admin.credential.cert({
      projectId: process.env.FIREBASE_PROJECT_ID,
      clientEmail: process.env.FIREBASE_CLIENT_EMAIL,
      privateKey: process.env.FIREBASE_PRIVATE_KEY.replace(/\\n/g, "\n"),
    });
  }

  if (credential) {
    admin.initializeApp({ credential });
  } else {
    // Fallback to application default credentials (useful in local dev / GCP environments)
    admin.initializeApp();
  }
}

const db = admin.firestore();

// Pass Tier Definitions
const PASS_TIERS = {
  daily_pass: {
    id: "daily_pass",
    title: "Daily Pass",
    priceInInr: 9,
    pointsCost: 100,
    durationMs: 24 * 60 * 60 * 1000, // 24 hours
  },
  weekly_pass: {
    id: "weekly_pass",
    title: "Weekly Pass",
    priceInInr: 49,
    pointsCost: 500,
    durationMs: 7 * 24 * 60 * 60 * 1000, // 7 days
  },
  monthly_pass: {
    id: "monthly_pass",
    title: "Monthly Pass",
    priceInInr: 179,
    pointsCost: 1500,
    durationMs: 28 * 24 * 60 * 60 * 1000, // 28 days
  },
};

const TRIAL_DURATION_MS = 48 * 60 * 60 * 1000; // 48 Hours

// Pay-Per-Use Feature Pricing (Points per Hour)
const FEATURE_PRICING = {
  auto_click: {
    id: "auto_click",
    title: "Auto Click Speed",
    pointsPerHour: 15,
  },
  fare_filters: {
    id: "fare_filters",
    title: "Fare Filters",
    pointsPerHour: 10,
  },
  distance_filters: {
    id: "distance_filters",
    title: "Distance Filters",
    pointsPerHour: 10,
  },
  tts_voice: {
    id: "tts_voice",
    title: "Voice Announcements",
    pointsPerHour: 10,
  },
  floating_overlay: {
    id: "floating_overlay",
    title: "HUD Window",
    pointsPerHour: 5,
  },
  destination_blacklist: {
    id: "destination_blacklist",
    title: "Destination Blacklist",
    pointsPerHour: 5,
  },
};

/**
 * Applies standard CORS headers and handles preflight OPTIONS requests.
 * Returns true if the request was handled (e.g. OPTIONS 200).
 */
function handleCors(req, res) {
  res.setHeader("Access-Control-Allow-Credentials", "true");
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
  res.setHeader(
    "Access-Control-Allow-Headers",
    "X-CSRF-Token, X-Requested-With, Accept, Accept-Version, Content-Length, Content-MD5, Content-Type, Date, X-Api-Version, Authorization"
  );

  if (req.method === "OPTIONS") {
    res.status(200).end();
    return true;
  }
  return false;
}

/**
 * Validates the Firebase Auth ID Token from the Authorization: Bearer <token> header.
 * Attaches decoded user to req.user.
 * Sends 401 error response and returns null if unauthenticated.
 */
async function authenticateUser(req, res) {
  const authHeader = req.headers.authorization || req.headers.Authorization;
  if (!authHeader || !authHeader.startsWith("Bearer ")) {
    res.status(401).json({
      error: "Unauthorized",
      message: "Missing or invalid Authorization header. Expected Bearer <token>.",
    });
    return null;
  }

  const token = authHeader.substring(7).trim();
  try {
    const decodedToken = await admin.auth().verifyIdToken(token);
    req.user = decodedToken;
    return decodedToken;
  } catch (error) {
    console.error("Token verification failed:", error.message);
    res.status(401).json({
      error: "Unauthorized",
      message: "Invalid or expired Firebase Auth token.",
    });
    return null;
  }
}

module.exports = {
  admin,
  db,
  PASS_TIERS,
  TRIAL_DURATION_MS,
  FEATURE_PRICING,
  handleCors,
  authenticateUser,
};
