const admin = require("firebase-admin");

if (!admin.apps.length) {
  if (process.env.FIREBASE_SERVICE_ACCOUNT) {
    try {
      const serviceAccount = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT);
      admin.initializeApp({
        credential: admin.credential.cert(serviceAccount),
      });
    } catch (e) {
      console.warn("Failed parsing FIREBASE_SERVICE_ACCOUNT, falling back to default:", e.message);
      admin.initializeApp();
    }
  } else {
    admin.initializeApp();
  }
}

const db = admin.firestore();

// Helper to authenticate caller via Firebase ID Token (Bearer <token>)
async function authenticateRequest(req) {
  const authHeader = req.headers?.authorization || req.headers?.Authorization || "";
  if (!authHeader.startsWith("Bearer ")) {
    return null;
  }
  const token = authHeader.substring(7).trim();
  if (!token) return null;

  try {
    const decoded = await admin.auth().verifyIdToken(token);
    return decoded;
  } catch (err) {
    console.warn("verifyIdToken error:", err.message);
    return null;
  }
}

// Robust CORS handler: sets headers and handles OPTIONS preflight gracefully
function handleCors(req, res) {
  if (res && typeof res.setHeader === "function") {
    res.setHeader("Access-Control-Allow-Origin", "*");
    res.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    res.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Requested-With, Accept");
  }
  if (req && req.method === "OPTIONS") {
    if (res && typeof res.status === "function") {
      res.status(200).end();
    }
    return true;
  }
  return false;
}

// Pass Tier Definitions (Duration in Milliseconds)
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

module.exports = {
  admin,
  db,
  authenticateRequest,
  handleCors,
  PASS_TIERS,
  TRIAL_DURATION_MS,
  FEATURE_PRICING,
};
