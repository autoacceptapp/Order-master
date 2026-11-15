const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const admin = require("firebase-admin");

if (!admin.apps.length) {
  admin.initializeApp();
}

const db = admin.firestore();

// Pass Tier Definitions (Duration in Milliseconds)
const PASS_TIERS = {
  daily_pass: {
    id: "daily_pass",
    title: "Daily Pass",
    priceInInr: 9,
    pointsCost: 100,
    durationMs: 24 * 60 * 60 * 1000, // 24 hours
    isProPass: true,
  },
  weekly_pass: {
    id: "weekly_pass",
    title: "Weekly Pass",
    priceInInr: 49,
    pointsCost: 500,
    durationMs: 7 * 24 * 60 * 60 * 1000, // 7 days
    isProPass: true,
  },
  monthly_pass: {
    id: "monthly_pass",
    title: "Monthly Pass",
    priceInInr: 179,
    pointsCost: 1500,
    durationMs: 28 * 24 * 60 * 60 * 1000, // 28 days
    isProPass: true,
  },
  remove_ads_pass: {
    id: "remove_ads_pass",
    title: "Remove Ads Pass",
    priceInInr: 199,
    pointsCost: 1600,
    durationMs: 28 * 24 * 60 * 60 * 1000, // 28 days
    isProPass: false,
  },
};

const TRIAL_DURATION_MS = 48 * 60 * 60 * 1000; // 48 Hours

// Pay-Per-Use Feature Pricing (Points per Hour) — 9 granular feature keys
const FEATURE_PRICING = {
  min_fare: {
    id: "min_fare",
    title: "Minimum Fare Filter",
    pointsPerHour: 10,
  },
  max_fare: {
    id: "max_fare",
    title: "Maximum Fare Filter",
    pointsPerHour: 10,
  },
  max_pickup_distance: {
    id: "max_pickup_distance",
    title: "Max Pickup Distance Filter",
    pointsPerHour: 10,
  },
  max_drop_distance: {
    id: "max_drop_distance",
    title: "Max Drop Distance Filter",
    pointsPerHour: 10,
  },
  min_per_km_rate: {
    id: "min_per_km_rate",
    title: "Minimum Rate Per-Km Filter",
    pointsPerHour: 15,
  },
  destination_blacklist: {
    id: "destination_blacklist",
    title: "Destination Blacklist Filter",
    pointsPerHour: 5,
  },
  tts_voice: {
    id: "tts_voice",
    title: "Voice Announcements (TTS)",
    pointsPerHour: 10,
  },
  floating_overlay: {
    id: "floating_overlay",
    title: "Floating Window Overlay",
    pointsPerHour: 10,
  },
  auto_click: {
    id: "auto_click",
    title: "Auto Click Speed (300ms Boost)",
    pointsPerHour: 15,
  },
};


/**
 * Validates Caller App Check and Authentication
 */
function assertCallerSecurity(request) {
  if (!request.auth) {
    throw new HttpsError(
      "unauthenticated",
      "Authentication required. User must be signed in with Google."
    );
  }

  // Enforce App Check if enabled in production
  if (process.env.ENFORCE_APP_CHECK === "true" && !request.app) {
    throw new HttpsError(
      "failed-precondition",
      "App Check verification failed. Unofficial or modified APK detected."
    );
  }
}

function parseBanUntilMs(userData) {
  if (!userData || userData.banUntil == null) return 0;
  const raw = userData.banUntil;
  if (typeof raw === "number") return raw;
  if (typeof raw.toMillis === "function") return raw.toMillis();
  if (raw instanceof Date) return raw.getTime();
  return 0;
}

function isFeatureLockedByAdmin(userData, featureId) {
  if (!userData || !featureId) return false;
  const target = String(featureId).trim().toLowerCase();
  const rawLocked = userData.locked_features || userData.lockedFeatures;
  const checkKey = (key) => {
    if (Array.isArray(rawLocked)) {
      return rawLocked.some((item) => String(item).trim().toLowerCase() === key);
    }
    if (rawLocked && typeof rawLocked === "object") {
      return Object.entries(rawLocked).some(
        ([k, v]) => v === true && String(k).trim().toLowerCase() === key
      );
    }
    return false;
  };
  if (checkKey(target)) return true;
  if (["min_fare", "max_fare", "min_per_km_rate"].includes(target) && checkKey("fare_filters")) {
    return true;
  }
  if (["max_pickup_distance", "max_drop_distance"].includes(target) && checkKey("distance_filters")) {
    return true;
  }
  return false;
}

async function assertGlobalServiceActiveAndNotBanned(uid) {
  const globalSnap = await db.collection("app_settings").document("global").get();
  if (globalSnap.exists) {
    const g = globalSnap.data() || {};
    if (g.isServiceActive === false) {
      throw new HttpsError(
        "unavailable",
        g.offlineMessage || g.message || "Service is currently offline for maintenance."
      );
    }
  }
  if (uid) {
    const userSnap = await db.collection("users").document(uid).get();
    if (userSnap.exists) {
      const banMs = parseBanUntilMs(userSnap.data());
      if (banMs > Date.now()) {
        throw new HttpsError(
          "permission-denied",
          "Account is temporarily suspended. Please contact support."
        );
      }
    }
  }
}

function generateReferralCode() {
  const allowedChars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  let randomSuffix = "";
  for (let i = 0; i < 6; i++) {
    randomSuffix += allowedChars.charAt(Math.floor(Math.random() * allowedChars.length));
  }
  return `OM${randomSuffix}`;
}

/**
 * 1. claimHardwareTrial
 * Grants a hardware-locked 48-hour trial using authoritative server time.
 * Prevents client-side time manipulation and re-claims.
 */
exports.claimHardwareTrial = onCall({ enforceAppCheck: false }, async (request) => {
  assertCallerSecurity(request);

  const uid = request.auth.uid;
  await assertGlobalServiceActiveAndNotBanned(uid);
  const userEmail = request.auth.token.email || "";
  const hardwareId = (request.data.hardwareId || "").trim();
  const deviceSpecs = request.data.deviceSpecs || {};

  if (!hardwareId || hardwareId.length < 8) {
    throw new HttpsError("invalid-argument", "Valid hardware ID required.");
  }

  const hardwareDocRef = db.collection("hardware_trials").document(hardwareId);
  const userTrialDocRef = db.collection("user_trials").document(uid);

  return db.runTransaction(async (transaction) => {
    const hwSnap = await transaction.get(hardwareDocRef);
    const userTrialSnap = await transaction.get(userTrialDocRef);

    const now = Date.now();

    // Check if hardware already has a claimed trial
    if (hwSnap.exists) {
      const data = hwSnap.data();
      return {
        success: true,
        isNewClaim: false,
        firstClaimedTimestamp: data.firstClaimedTimestamp || now,
        trialExpiryTimestamp: data.trialExpiryTimestamp || (now + TRIAL_DURATION_MS),
        isUsed: true,
        message: "Existing trial active on this hardware.",
      };
    }

    // Check if user already claimed a trial on another device
    if (userTrialSnap.exists) {
      throw new HttpsError(
        "already-exists",
        "Your Google account has already claimed a free trial on another device."
      );
    }

    const expiryTimestamp = now + TRIAL_DURATION_MS;

    const trialRecord = {
      hardwareId,
      claimedByUid: uid,
      userEmail,
      firstClaimedTimestamp: now,
      trialExpiryTimestamp: expiryTimestamp,
      isUsed: true,
      deviceSpecs,
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
      createdServerTimeMs: now,
    };

    transaction.set(hardwareDocRef, trialRecord);
    transaction.set(userTrialDocRef, {
      uid,
      hardwareId,
      claimedAt: admin.firestore.FieldValue.serverTimestamp(),
      claimedServerTimeMs: now,
    });

    return {
      success: true,
      isNewClaim: true,
      firstClaimedTimestamp: now,
      trialExpiryTimestamp: expiryTimestamp,
      isUsed: true,
      message: "2-Day Free Trial activated successfully.",
    };
  });
});

/**
 * 2. addPoints
 * Disabled for direct client calls to enforce Zero-Trust security.
 * Points may only be granted via verified server/admin flows (e.g. claimReferralCode, Admin SDK).
 */
exports.addPoints = onCall({ enforceAppCheck: false }, async (request) => {
  assertCallerSecurity(request);
  throw new HttpsError(
    "permission-denied",
    "Client-side point grants are disabled. Points can only be credited via verified referrals, ad claims, or Admin."
  );
});

/**
 * 3. purchasePassWithPoints
 * Verifies points balance and calculates expiry entirely on server.
 */
exports.purchasePassWithPoints = onCall({ enforceAppCheck: false }, async (request) => {
  assertCallerSecurity(request);

  const uid = request.auth.uid;
  await assertGlobalServiceActiveAndNotBanned(uid);
  const passTierId = request.data.passTier || request.data.passTierId;
  const tier = PASS_TIERS[passTierId];

  if (!tier) {
    throw new HttpsError("invalid-argument", `Unknown pass tier: ${passTierId}`);
  }

  const userDocRef = db.collection("users").document(uid);

  return db.runTransaction(async (transaction) => {
    const userSnap = await transaction.get(userDocRef);
    if (!userSnap.exists) {
      throw new HttpsError("not-found", "User licensing document not found.");
    }

    const userData = userSnap.data();
    const currentPoints = userData.pointsBalance || 0;

    if (currentPoints < tier.pointsCost) {
      throw new HttpsError(
        "failed-precondition",
        `Insufficient points. Balance: ${currentPoints}, Required: ${tier.pointsCost}`
      );
    }

    const serverNow = Date.now();
    const newPoints = currentPoints - tier.pointsCost;

    if (!tier.isProPass) {
      // Remove Ads Pass ONLY removes ads and must NEVER grant Pro access
      const currentAdsExpiry = userData.removeAdsExpiryTimestamp || 0;
      const adsBaseTime = currentAdsExpiry > serverNow ? currentAdsExpiry : serverNow;
      const newAdsExpiry = adsBaseTime + tier.durationMs;

      transaction.set(
        userDocRef,
        {
          pointsBalance: newPoints,
          removeAdsExpiryTimestamp: newAdsExpiry,
          lastPurchasedTier: tier.id,
          lastPaymentMethod: "POINTS",
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
          updatedServerTimeMs: serverNow,
        },
        { merge: true }
      );

      return {
        success: true,
        activePassType: userData.activePassType || null,
        passExpiryTimestamp: userData.passExpiryTimestamp || 0,
        removeAdsExpiryTimestamp: newAdsExpiry,
        newPointsBalance: newPoints,
        serverTimeMs: serverNow,
      };
    }

    const currentExpiry = userData.passExpiryTimestamp || 0;
    const baseTime = currentExpiry > serverNow ? currentExpiry : serverNow;
    const newExpiry = baseTime + tier.durationMs;

    transaction.set(
      userDocRef,
      {
        pointsBalance: newPoints,
        activePassType: tier.id,
        passExpiryTimestamp: newExpiry,
        lastPurchasedTier: tier.id,
        lastPaymentMethod: "POINTS",
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        updatedServerTimeMs: serverNow,
      },
      { merge: true }
    );

    return {
      success: true,
      activePassType: tier.id,
      passExpiryTimestamp: newExpiry,
      removeAdsExpiryTimestamp: userData.removeAdsExpiryTimestamp || 0,
      newPointsBalance: newPoints,
      serverTimeMs: serverNow,
    };
  });
});

/**
 * 4. verifyUtrAndActivatePass
 * Validates bank UTR payment record server-side and extends pass expiry.
 */
exports.verifyUtrAndActivatePass = onCall({ enforceAppCheck: false }, async (request) => {
  assertCallerSecurity(request);

  const uid = request.auth.uid;
  await assertGlobalServiceActiveAndNotBanned(uid);
  const utr = (request.data.utr || "").trim();
  const passTierId = request.data.passTier || request.data.passTierId || "daily_pass";
  const tier = PASS_TIERS[passTierId];

  if (!tier) {
    throw new HttpsError("invalid-argument", `Unknown pass tier: ${passTierId}`);
  }

  if (!/^\d{12}$/.test(utr)) {
    throw new HttpsError("invalid-argument", "Invalid 12-digit numeric UTR.");
  }

  const paymentDocRef = db.collection("received_payments").document(utr);
  const userDocRef = db.collection("users").document(uid);

  return db.runTransaction(async (transaction) => {
    const paymentSnap = await transaction.get(paymentDocRef);

    if (!paymentSnap.exists) {
      // Record pending verification matching firestore.rules schema for admin review
      const pendingRef = db.collection("pending_verifications").document(utr);
      transaction.set(
        pendingRef,
        {
          utr,
          userId: uid,
          firebaseUid: uid,
          passTier: tier.id,
          passName: tier.title,
          amount: tier.priceInInr,
          isUsed: false,
          status: "PENDING",
          timestamp: admin.firestore.FieldValue.serverTimestamp(),
        },
        { merge: true }
      );

      throw new HttpsError(
        "not-found",
        "Payment UTR not confirmed by bank yet. Pending admin verification."
      );
    }

    const paymentData = paymentSnap.data();
    if (paymentData.isClaimed || paymentData.claimedByUid || paymentData.isUsed) {
      throw new HttpsError("already-exists", "This UTR has already been claimed or used.");
    }

    if (paymentData.status && paymentData.status !== "Verified" && paymentData.status !== "VERIFIED") {
      throw new HttpsError("failed-precondition", "Payment has not been verified yet.");
    }

    const paidAmount = Number(paymentData.amount || 0);
    const explicitTier = paymentData.passTier || paymentData.tier;
    if (paidAmount < tier.priceInInr && explicitTier !== tier.id) {
      throw new HttpsError(
        "failed-precondition",
        `Paid amount (₹${paidAmount}) is below required ₹${tier.priceInInr} for ${tier.title}.`
      );
    }

    const serverNow = Date.now();
    const pendingRef = db.collection("pending_verifications").document(utr);
    const [userSnap, pendingSnap] = await Promise.all([
      transaction.get(userDocRef),
      transaction.get(pendingRef),
    ]);
    const userData = userSnap.exists ? userSnap.data() : {};

    // Mark payment as claimed and used
    transaction.update(paymentDocRef, {
      isUsed: true,
      isClaimed: true,
      claimedBy: uid,
      claimedByUid: uid,
      claimedByUserId: uid,
      claimedAt: admin.firestore.FieldValue.serverTimestamp(),
      passTier: tier.id,
    });

    if (pendingSnap.exists) {
      transaction.update(pendingRef, {
        status: "Verified",
        isUsed: true,
        verifiedAt: admin.firestore.FieldValue.serverTimestamp(),
      });
    }

    if (!tier.isProPass) {
      // Remove Ads Pass strictly removes banner ads only
      const currentAdsExpiry = userData.removeAdsExpiryTimestamp || 0;
      const adsBaseTime = currentAdsExpiry > serverNow ? currentAdsExpiry : serverNow;
      const newAdsExpiry = adsBaseTime + tier.durationMs;

      transaction.set(
        userDocRef,
        {
          userId: uid,
          removeAdsExpiryTimestamp: newAdsExpiry,
          lastPurchasedTier: tier.id,
          lastPaymentMethod: "UPI_UTR",
          lastTransactionId: utr,
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
          updatedServerTimeMs: serverNow,
        },
        { merge: true }
      );

      return {
        success: true,
        activePassType: userData.activePassType || null,
        passExpiryTimestamp: userData.passExpiryTimestamp || 0,
        removeAdsExpiryTimestamp: newAdsExpiry,
        serverTimeMs: serverNow,
      };
    }

    const currentExpiry = userData.passExpiryTimestamp || 0;
    const baseTime = currentExpiry > serverNow ? currentExpiry : serverNow;
    const newExpiry = baseTime + tier.durationMs;

    // Update user's Pro pass licensing
    transaction.set(
      userDocRef,
      {
        userId: uid,
        activePassType: tier.id,
        passExpiryTimestamp: newExpiry,
        lastPurchasedTier: tier.id,
        lastPaymentMethod: "UPI_UTR",
        lastTransactionId: utr,
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        updatedServerTimeMs: serverNow,
      },
      { merge: true }
    );

    return {
      success: true,
      activePassType: tier.id,
      passExpiryTimestamp: newExpiry,
      removeAdsExpiryTimestamp: userData.removeAdsExpiryTimestamp || 0,
      serverTimeMs: serverNow,
    };
  });
});

/**
 * 5. getServerTime
 * Authoritative server timestamp endpoint for anti-clock-tampering verification.
 */
exports.getServerTime = onCall({ enforceAppCheck: false }, async (request) => {
  return {
    serverTimestampMs: Date.now(),
  };
});

/**
 * 6. initializeUserLicensing
 * Creates or ensures user licensing record in users with unique referral code.
 */
exports.initializeUserLicensing = onCall({ enforceAppCheck: false }, async (request) => {
  assertCallerSecurity(request);

  const uid = request.auth.uid;
  const userEmail = request.auth.token.email || "";
  const userDocRef = db.collection("users").document(uid);

  return db.runTransaction(async (transaction) => {
    const userSnap = await transaction.get(userDocRef);

    if (userSnap.exists) {
      const data = userSnap.data();
      let referralCode = data.referralCode;
      if (!referralCode) {
        const allowedChars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        let randomSuffix = "";
        for (let i = 0; i < 6; i++) {
          randomSuffix += allowedChars.charAt(Math.floor(Math.random() * allowedChars.length));
        }
        referralCode = `OM${randomSuffix}`;
        transaction.update(userDocRef, {
          referralCode,
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        });
      }
      return {
        success: true,
        isNew: false,
        referralCode,
        pointsBalance: data.pointsBalance || 0,
        hasClaimedReferral: !!data.hasClaimedReferral,
        referralCount: data.referralCount || 0,
      };
    }

    const allowedChars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    let randomSuffix = "";
    for (let i = 0; i < 6; i++) {
      randomSuffix += allowedChars.charAt(Math.floor(Math.random() * allowedChars.length));
    }
    const newCode = `OM${randomSuffix}`;

    const newRecord = {
      userId: uid,
      userEmail: userEmail,
      pointsBalance: 0,
      referralCode: newCode,
      hasClaimedReferral: false,
      referralCount: 0,
      activePassType: null,
      passExpiryTimestamp: 0,
      unlocked_features: {},
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    };

    transaction.set(userDocRef, newRecord);

    return {
      success: true,
      isNew: true,
      referralCode: newCode,
      pointsBalance: 0,
      hasClaimedReferral: false,
      referralCount: 0,
    };
  });
});

/**
 * 7. claimReferralCode
 * Atomic transaction to claim referral rewards (+20 to referrer, +50 to referee).
 */
exports.claimReferralCode = onCall({ enforceAppCheck: false }, async (request) => {
  assertCallerSecurity(request);

  const uid = request.auth.uid;
  await assertGlobalServiceActiveAndNotBanned(uid);
  const submittedCode = (request.data.submittedCode || request.data.inviteCode || "").trim().toUpperCase();

  if (!submittedCode || submittedCode.length !== 8) {
    throw new HttpsError("invalid-argument", "Referral code must be exactly 8 characters.");
  }

  // Pre-query referrer
  const querySnap = await db.collection("users")
    .where("referralCode", "==", submittedCode)
    .limit(1)
    .get();

  if (querySnap.empty) {
    throw new HttpsError("not-found", "Invalid referral code.");
  }

  const referrerDocSnap = querySnap.docs[0];
  const referrerUid = referrerDocSnap.id;

  if (referrerUid === uid) {
    throw new HttpsError("invalid-argument", "Cannot use your own referral code.");
  }

  const userDocRef = db.collection("users").document(uid);
  const referrerDocRef = db.collection("users").document(referrerUid);

  return db.runTransaction(async (transaction) => {
    const userSnap = await transaction.get(userDocRef);
    const freshReferrerSnap = await transaction.get(referrerDocRef);

    if (!freshReferrerSnap.exists) {
      throw new HttpsError("not-found", "Referrer document not found.");
    }

    const userData = userSnap.exists ? userSnap.data() : {};
    if (userData.hasClaimedReferral) {
      throw new HttpsError("already-exists", "Bonus Already Claimed.");
    }

    const currentPoints = userData.pointsBalance || 0;
    const referrerPoints = freshReferrerSnap.data().pointsBalance || 0;
    const referrerCount = freshReferrerSnap.data().referralCount || 0;

    const newCallerPoints = currentPoints + 50;
    const newReferrerPoints = referrerPoints + 20;

    // Update referrer
    transaction.set(
      referrerDocRef,
      {
        pointsBalance: newReferrerPoints,
        referralCount: referrerCount + 1,
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      },
      { merge: true }
    );

    // Update referee
    transaction.set(
      userDocRef,
      {
        userId: uid,
        pointsBalance: newCallerPoints,
        hasClaimedReferral: true,
        claimedFromCode: submittedCode,
        claimedAt: admin.firestore.FieldValue.serverTimestamp(),
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      },
      { merge: true }
    );

    return {
      success: true,
      newPointsBalance: newCallerPoints,
      message: "Referral claimed: +50 points added to your balance.",
    };
  });
});

/**
 * 8. unlockFeatureWithPoints
 * Pay-Per-Use micro-transaction to temporarily unlock specific premium features on an hourly basis.
 * Validates points balance, deducts points, and extends feature expiry timestamp.
 */
exports.unlockFeatureWithPoints = onCall({ enforceAppCheck: false }, async (request) => {
  assertCallerSecurity(request);

  const uid = request.auth.uid;
  await assertGlobalServiceActiveAndNotBanned(uid);
  const featureId = (request.data.featureId || "").trim().toLowerCase();
  const hours = parseInt(request.data.hours, 10) || 1;

  if (isNaN(hours) || hours <= 0 || hours > 720) {
    throw new HttpsError("invalid-argument", "Hours must be a positive integer (max 720).");
  }

  const pricing = FEATURE_PRICING[featureId];
  if (!pricing) {
    throw new HttpsError("invalid-argument", `Unknown feature ID: ${featureId}`);
  }

  const totalCost = pricing.pointsPerHour * hours;
  const userDocRef = db.collection("users").document(uid);

  return db.runTransaction(async (transaction) => {
    const userSnap = await transaction.get(userDocRef);
    if (!userSnap.exists) {
      throw new HttpsError("not-found", "User licensing document not found.");
    }

    const userData = userSnap.data();
    if (isFeatureLockedByAdmin(userData, featureId)) {
      throw new HttpsError("permission-denied", `${pricing.title} is locked by Admin.`);
    }
    const currentPoints = userData.pointsBalance || 0;

    if (currentPoints < totalCost) {
      throw new HttpsError(
        "failed-precondition",
        `Insufficient points. Balance: ${currentPoints}, Required: ${totalCost}`
      );
    }

    const serverNow = Date.now();
    const unlockedFeatures = userData.unlocked_features || {};
    const currentExpiry = unlockedFeatures[featureId] || 0;
    const baseTime = currentExpiry > serverNow ? currentExpiry : serverNow;
    const newExpiry = baseTime + (hours * 3600000);
    const newPoints = currentPoints - totalCost;

    transaction.set(
      userDocRef,
      {
        pointsBalance: newPoints,
        unlocked_features: {
          ...unlockedFeatures,
          [featureId]: newExpiry,
        },
        lastFeatureUnlock: {
          featureId,
          hours,
          pointsCost: totalCost,
          unlockedAt: admin.firestore.FieldValue.serverTimestamp(),
          newExpiry,
        },
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        updatedServerTimeMs: serverNow,
      },
      { merge: true }
    );

    return {
      success: true,
      featureId,
      hours,
      pointsDeducted: totalCost,
      newPointsBalance: newPoints,
      expiryTimestamp: newExpiry,
      serverTimeMs: serverNow,
      message: `Unlocked ${pricing.title} for ${hours} hour(s) until ${new Date(newExpiry).toISOString()}`,
    };
  });
});

/**
 * 9. processPendingAction
 * Zero-Trust Action Queue processor handling client-initiated PENDING requests
 * atomically with strict server-side pricing, Admin lock checks, and cooldowns.
 */
exports.processPendingAction = onDocumentCreated("pending_actions/{docId}", async (event) => {
  const snap = event.data;
  if (!snap) return;

  const data = snap.data() || {};
  if (data.status !== "PENDING") return;

  const actionRef = snap.ref;
  const uid = String(data.firebaseUid || data.userId || "").trim();
  if (!uid) {
    await actionRef.update({
      status: "FAILED",
      errorMessage: "Missing authenticated user ID.",
      processedAt: admin.firestore.FieldValue.serverTimestamp(),
    });
    return;
  }

  try {
    await assertGlobalServiceActiveAndNotBanned(uid);
    const userDocRef = db.collection("users").document(uid);
    let completionPayload = {};

    switch (data.actionType) {
      case "INIT_PROFILE":
      case "UPDATE_PROFILE":
      case "MIGRATE_PROFILE": {
        completionPayload = await db.runTransaction(async (transaction) => {
          const userSnap = await transaction.get(userDocRef);
          const serverNow = Date.now();
          if (userSnap.exists) {
            const existing = userSnap.data() || {};
            const code = existing.referralCode || generateReferralCode();
            const updates = {
              userId: uid,
              firebaseUid: uid,
              updatedAt: admin.firestore.FieldValue.serverTimestamp(),
              updatedServerTimeMs: serverNow,
            };
            if (data.userEmail) updates.userEmail = String(data.userEmail).slice(0, 256);
            if (data.linkedHardwareId) updates.linkedHardwareId = String(data.linkedHardwareId).slice(0, 128);
            if (!existing.referralCode) updates.referralCode = code;
            transaction.set(userDocRef, updates, { merge: true });
            return {
              referralCode: code,
              pointsBalance: existing.pointsBalance || 0,
              newPointsBalance: existing.pointsBalance || 0,
              serverTimeMs: serverNow,
            };
          } else {
            const code = generateReferralCode();
            const newProfile = {
              userId: uid,
              firebaseUid: uid,
              userEmail: String(data.userEmail || "").slice(0, 256),
              linkedHardwareId: String(data.linkedHardwareId || "").slice(0, 128),
              pointsBalance: 0,
              referralCode: code,
              hasClaimedReferral: false,
              referralCount: 0,
              activePassType: null,
              passExpiryTimestamp: 0,
              removeAdsExpiryTimestamp: 0,
              unlocked_features: {},
              locked_features: [],
              createdAt: admin.firestore.FieldValue.serverTimestamp(),
              updatedAt: admin.firestore.FieldValue.serverTimestamp(),
              updatedServerTimeMs: serverNow,
            };
            transaction.set(userDocRef, newProfile);
            return {
              referralCode: code,
              pointsBalance: 0,
              newPointsBalance: 0,
              serverTimeMs: serverNow,
            };
          }
        });
        break;
      }

      case "CLAIM_REFERRAL": {
        const submittedCode = String(data.referralCode || "").trim().toUpperCase();
        if (!submittedCode || submittedCode.length !== 8) {
          throw new Error("Referral code must be exactly 8 characters.");
        }
        const querySnap = await db.collection("users")
          .where("referralCode", "==", submittedCode)
          .limit(1)
          .get();
        if (querySnap.empty) {
          throw new Error("Invalid referral code.");
        }
        const referrerUid = querySnap.docs[0].id;
        if (referrerUid === uid) {
          throw new Error("Cannot use your own referral code.");
        }
        const referrerDocRef = db.collection("users").document(referrerUid);
        completionPayload = await db.runTransaction(async (transaction) => {
          const [userSnap, refSnap] = await Promise.all([
            transaction.get(userDocRef),
            transaction.get(referrerDocRef),
          ]);
          if (!refSnap.exists) throw new Error("Referrer account not found.");
          const userData = userSnap.exists ? userSnap.data() : {};
          if (userData.hasClaimedReferral) throw new Error("Bonus Already Claimed.");
          const serverNow = Date.now();
          const newCallerPoints = (userData.pointsBalance || 0) + 50;
          const newReferrerPoints = (refSnap.data().pointsBalance || 0) + 20;
          transaction.set(
            referrerDocRef,
            {
              pointsBalance: newReferrerPoints,
              referralCount: (refSnap.data().referralCount || 0) + 1,
              updatedAt: admin.firestore.FieldValue.serverTimestamp(),
              updatedServerTimeMs: serverNow,
            },
            { merge: true }
          );
          transaction.set(
            userDocRef,
            {
              userId: uid,
              firebaseUid: uid,
              pointsBalance: newCallerPoints,
              hasClaimedReferral: true,
              claimedFromCode: submittedCode,
              claimedAt: admin.firestore.FieldValue.serverTimestamp(),
              updatedAt: admin.firestore.FieldValue.serverTimestamp(),
              updatedServerTimeMs: serverNow,
            },
            { merge: true }
          );
          return { newPointsBalance: newCallerPoints, serverTimeMs: serverNow };
        });
        break;
      }

      case "REWARD_AD_WATCHED": {
        completionPayload = await db.runTransaction(async (transaction) => {
          const userSnap = await transaction.get(userDocRef);
          const userData = userSnap.exists ? userSnap.data() : {};
          const serverNow = Date.now();
          const lastRewardMs = Number(userData.lastAdRewardAtMs || 0);
          if (lastRewardMs > 0 && serverNow - lastRewardMs < 30000) {
            throw new Error("Please wait 30 seconds before claiming another ad reward.");
          }
          const utcDay = new Date(serverNow).toISOString().slice(0, 10);
          const prevDay = userData.dailyAdRewardDate || "";
          const dayCount = prevDay === utcDay ? Number(userData.dailyAdRewardCount || 0) : 0;
          if (dayCount >= 20) {
            throw new Error("Daily rewarded ad limit reached. Please try again tomorrow.");
          }
          const reward = 5;
          const newPoints = (userData.pointsBalance || 0) + reward;
          transaction.set(
            userDocRef,
            {
              userId: uid,
              firebaseUid: uid,
              pointsBalance: newPoints,
              lastAdRewardAtMs: serverNow,
              dailyAdRewardDate: utcDay,
              dailyAdRewardCount: dayCount + 1,
              updatedAt: admin.firestore.FieldValue.serverTimestamp(),
              updatedServerTimeMs: serverNow,
            },
            { merge: true }
          );
          return { rewardAmount: reward, newPointsBalance: newPoints, serverTimeMs: serverNow };
        });
        break;
      }

      case "PURCHASE_PASS": {
        const tier = PASS_TIERS[String(data.passTier || "").trim()];
        if (!tier) throw new Error(`Unknown pass tier: ${data.passTier}`);
        completionPayload = await db.runTransaction(async (transaction) => {
          const userSnap = await transaction.get(userDocRef);
          if (!userSnap.exists) throw new Error("User licensing document not found.");
          const userData = userSnap.data() || {};
          const currentPoints = userData.pointsBalance || 0;
          if (currentPoints < tier.pointsCost) {
            throw new Error(`Insufficient points. Need ${tier.pointsCost}, balance is ${currentPoints}.`);
          }
          const serverNow = Date.now();
          const newPoints = currentPoints - tier.pointsCost;
          if (!tier.isProPass) {
            const curAds = userData.removeAdsExpiryTimestamp || 0;
            const newAdsExpiry = (curAds > serverNow ? curAds : serverNow) + tier.durationMs;
            transaction.set(
              userDocRef,
              {
                pointsBalance: newPoints,
                removeAdsExpiryTimestamp: newAdsExpiry,
                lastPurchasedTier: tier.id,
                lastPaymentMethod: "POINTS",
                updatedAt: admin.firestore.FieldValue.serverTimestamp(),
                updatedServerTimeMs: serverNow,
              },
              { merge: true }
            );
            return {
              activePassType: userData.activePassType || null,
              passExpiryTimestamp: userData.passExpiryTimestamp || 0,
              removeAdsExpiryTimestamp: newAdsExpiry,
              newPointsBalance: newPoints,
              serverTimeMs: serverNow,
            };
          } else {
            const curPass = userData.passExpiryTimestamp || 0;
            const newExpiry = (curPass > serverNow ? curPass : serverNow) + tier.durationMs;
            transaction.set(
              userDocRef,
              {
                pointsBalance: newPoints,
                activePassType: tier.id,
                passExpiryTimestamp: newExpiry,
                lastPurchasedTier: tier.id,
                lastPaymentMethod: "POINTS",
                updatedAt: admin.firestore.FieldValue.serverTimestamp(),
                updatedServerTimeMs: serverNow,
              },
              { merge: true }
            );
            return {
              activePassType: tier.id,
              passExpiryTimestamp: newExpiry,
              removeAdsExpiryTimestamp: userData.removeAdsExpiryTimestamp || 0,
              newPointsBalance: newPoints,
              serverTimeMs: serverNow,
            };
          }
        });
        break;
      }

      case "ACTIVATE_PASS_PAYMENT": {
        const utr = String(data.utr || data.transactionId || "").trim();
        const tier = PASS_TIERS[String(data.passTier || "daily_pass").trim()];
        if (!tier) throw new Error(`Unknown pass tier: ${data.passTier}`);
        if (!/^\d{12}$/.test(utr)) throw new Error("Invalid 12-digit numeric UTR.");

        const paymentDocRef = db.collection("received_payments").document(utr);
        const pendingRef = db.collection("pending_verifications").document(utr);
        completionPayload = await db.runTransaction(async (transaction) => {
          const [paymentSnap, userSnap, pendingSnap] = await Promise.all([
            transaction.get(paymentDocRef),
            transaction.get(userDocRef),
            transaction.get(pendingRef),
          ]);
          if (!paymentSnap.exists) {
            throw new Error("Payment not verified yet. Please wait for bank SMS confirmation.");
          }
          const paymentData = paymentSnap.data() || {};
          if (paymentData.isClaimed || paymentData.claimedByUid || paymentData.isUsed) {
            throw new Error("This UTR has already been claimed on another account.");
          }
          if (paymentData.status && paymentData.status !== "Verified" && paymentData.status !== "VERIFIED") {
            throw new Error("Payment has not been verified yet.");
          }
          const paidAmount = Number(paymentData.amount || 0);
          const explicitTier = paymentData.passTier || paymentData.tier;
          if (paidAmount < tier.priceInInr && explicitTier !== tier.id) {
            throw new Error(`Paid amount (₹${paidAmount}) is less than ₹${tier.priceInInr} required for ${tier.title}.`);
          }
          const serverNow = Date.now();
          const userData = userSnap.exists ? userSnap.data() : {};
          transaction.update(paymentDocRef, {
            isUsed: true,
            isClaimed: true,
            claimedBy: uid,
            claimedByUid: uid,
            claimedByUserId: uid,
            claimedAt: admin.firestore.FieldValue.serverTimestamp(),
            passTier: tier.id,
          });
          if (pendingSnap.exists) {
            transaction.update(pendingRef, {
              status: "Verified",
              isUsed: true,
              verifiedAt: admin.firestore.FieldValue.serverTimestamp(),
            });
          }
          if (!tier.isProPass) {
            const curAds = userData.removeAdsExpiryTimestamp || 0;
            const newAdsExpiry = (curAds > serverNow ? curAds : serverNow) + tier.durationMs;
            transaction.set(
              userDocRef,
              {
                userId: uid,
                firebaseUid: uid,
                removeAdsExpiryTimestamp: newAdsExpiry,
                lastPurchasedTier: tier.id,
                lastPaymentMethod: "UPI_UTR",
                lastTransactionId: utr,
                updatedAt: admin.firestore.FieldValue.serverTimestamp(),
                updatedServerTimeMs: serverNow,
              },
              { merge: true }
            );
            return {
              activePassType: userData.activePassType || null,
              passExpiryTimestamp: userData.passExpiryTimestamp || 0,
              removeAdsExpiryTimestamp: newAdsExpiry,
              serverTimeMs: serverNow,
            };
          } else {
            const curPass = userData.passExpiryTimestamp || 0;
            const newExpiry = (curPass > serverNow ? curPass : serverNow) + tier.durationMs;
            transaction.set(
              userDocRef,
              {
                userId: uid,
                firebaseUid: uid,
                activePassType: tier.id,
                passExpiryTimestamp: newExpiry,
                lastPurchasedTier: tier.id,
                lastPaymentMethod: "UPI_UTR",
                lastTransactionId: utr,
                updatedAt: admin.firestore.FieldValue.serverTimestamp(),
                updatedServerTimeMs: serverNow,
              },
              { merge: true }
            );
            return {
              activePassType: tier.id,
              passExpiryTimestamp: newExpiry,
              removeAdsExpiryTimestamp: userData.removeAdsExpiryTimestamp || 0,
              serverTimeMs: serverNow,
            };
          }
        });
        break;
      }

      case "UNLOCK_FEATURE": {
        const featureId = String(data.featureId || "").trim().toLowerCase();
        const hours = parseInt(data.hours, 10) || 1;
        if (hours <= 0 || hours > 720) throw new Error("Hours must be between 1 and 720.");
        const pricing = FEATURE_PRICING[featureId];
        if (!pricing) throw new Error(`Unknown feature ID: ${featureId}`);
        const totalCost = pricing.pointsPerHour * hours;
        completionPayload = await db.runTransaction(async (transaction) => {
          const userSnap = await transaction.get(userDocRef);
          if (!userSnap.exists) throw new Error("User licensing document not found.");
          const userData = userSnap.data() || {};
          if (isFeatureLockedByAdmin(userData, featureId)) {
            throw new Error(`${pricing.title} is locked by Admin.`);
          }
          const currentPoints = userData.pointsBalance || 0;
          if (currentPoints < totalCost) {
            throw new Error(`Insufficient points. Balance: ${currentPoints}, Required: ${totalCost}`);
          }
          const serverNow = Date.now();
          const unlocked = { ...(userData.unlocked_features || {}) };
          const curExp = Number(unlocked[featureId] || 0);
          const newExpiry = (curExp > serverNow ? curExp : serverNow) + hours * 3600000;
          unlocked[featureId] = newExpiry;
          const newPoints = currentPoints - totalCost;
          transaction.set(
            userDocRef,
            {
              pointsBalance: newPoints,
              unlocked_features: unlocked,
              updatedAt: admin.firestore.FieldValue.serverTimestamp(),
              updatedServerTimeMs: serverNow,
            },
            { merge: true }
          );
          return {
            featureId,
            expiryTimestamp: newExpiry,
            newPointsBalance: newPoints,
            serverTimeMs: serverNow,
          };
        });
        break;
      }

      case "UNLOCK_MULTIPLE_FEATURES": {
        const rawIds = Array.isArray(data.featureIds) ? data.featureIds : [];
        const featureIds = [...new Set(rawIds.map((x) => String(x || "").trim().toLowerCase()).filter(Boolean))];
        const hours = parseInt(data.hours, 10) || 1;
        if (featureIds.length === 0 || featureIds.length > 10) {
          throw new Error("Select between 1 and 10 features.");
        }
        if (hours <= 0 || hours > 720) throw new Error("Hours must be between 1 and 720.");
        const pricings = featureIds.map((fid) => {
          const p = FEATURE_PRICING[fid];
          if (!p) throw new Error(`Unknown feature ID: ${fid}`);
          return p;
        });
        const totalCost = pricings.reduce((sum, p) => sum + p.pointsPerHour * hours, 0);
        completionPayload = await db.runTransaction(async (transaction) => {
          const userSnap = await transaction.get(userDocRef);
          if (!userSnap.exists) throw new Error("User licensing document not found.");
          const userData = userSnap.data() || {};
          for (const p of pricings) {
            if (isFeatureLockedByAdmin(userData, p.id)) {
              throw new Error(`${p.title} is locked by Admin.`);
            }
          }
          const currentPoints = userData.pointsBalance || 0;
          if (currentPoints < totalCost) {
            throw new Error(`Insufficient points. Balance: ${currentPoints}, Required: ${totalCost}`);
          }
          const serverNow = Date.now();
          const unlocked = { ...(userData.unlocked_features || {}) };
          const unlockedExpiries = {};
          for (const p of pricings) {
            const curExp = Number(unlocked[p.id] || 0);
            const newExpiry = (curExp > serverNow ? curExp : serverNow) + hours * 3600000;
            unlocked[p.id] = newExpiry;
            unlockedExpiries[p.id] = newExpiry;
          }
          const newPoints = currentPoints - totalCost;
          transaction.set(
            userDocRef,
            {
              pointsBalance: newPoints,
              unlocked_features: unlocked,
              updatedAt: admin.firestore.FieldValue.serverTimestamp(),
              updatedServerTimeMs: serverNow,
            },
            { merge: true }
          );
          return {
            unlockedExpiries,
            totalPointsDeducted: totalCost,
            newPointsBalance: newPoints,
            serverTimeMs: serverNow,
          };
        });
        break;
      }

      default:
        throw new Error(`Unsupported actionType: ${data.actionType}`);
    }

    await actionRef.update({
      status: "COMPLETED",
      ...completionPayload,
      processedAt: admin.firestore.FieldValue.serverTimestamp(),
    });
  } catch (err) {
    await actionRef.update({
      status: "FAILED",
      errorMessage: err.message || "Action processing failed.",
      processedAt: admin.firestore.FieldValue.serverTimestamp(),
    });
  }
});

