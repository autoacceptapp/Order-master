const { onCall, HttpsError } = require("firebase-functions/v2/https");
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

/**
 * 1. claimHardwareTrial
 * Grants a hardware-locked 48-hour trial using authoritative server time.
 * Prevents client-side time manipulation and re-claims.
 */
exports.claimHardwareTrial = onCall({ enforceAppCheck: false }, async (request) => {
  assertCallerSecurity(request);

  const uid = request.auth.uid;
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
 * Secure server-side points credit (e.g. from referrals or wallet top-ups).
 */
exports.addPoints = onCall({ enforceAppCheck: false }, async (request) => {
  assertCallerSecurity(request);

  const uid = request.auth.uid;
  const amount = parseInt(request.data.amount, 10);
  const reason = request.data.reason || "Wallet Top-up";

  if (isNaN(amount) || amount <= 0 || amount > 10000) {
    throw new HttpsError("invalid-argument", "Amount must be a positive number up to 10,000.");
  }

  const userDocRef = db.collection("users_licensing").document(uid);

  return db.runTransaction(async (transaction) => {
    const userSnap = await transaction.get(userDocRef);
    const currentPoints = userSnap.exists ? (userSnap.data().pointsBalance || 0) : 0;
    const newPoints = currentPoints + amount;

    transaction.set(
      userDocRef,
      {
        userId: uid,
        pointsBalance: newPoints,
        lastPointsCredit: {
          amount,
          reason,
          creditedAt: admin.firestore.FieldValue.serverTimestamp(),
        },
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      },
      { merge: true }
    );

    return {
      success: true,
      addedPoints: amount,
      newPointsBalance: newPoints,
    };
  });
});

/**
 * 3. purchasePassWithPoints
 * Verifies points balance and calculates expiry entirely on server.
 */
exports.purchasePassWithPoints = onCall({ enforceAppCheck: false }, async (request) => {
  assertCallerSecurity(request);

  const uid = request.auth.uid;
  const passTierId = request.data.passTier || request.data.passTierId;
  const tier = PASS_TIERS[passTierId];

  if (!tier) {
    throw new HttpsError("invalid-argument", `Unknown pass tier: ${passTierId}`);
  }

  const userDocRef = db.collection("users_licensing").document(uid);

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
    const currentExpiry = userData.passExpiryTimestamp || 0;
    const baseTime = currentExpiry > serverNow ? currentExpiry : serverNow;
    const newExpiry = baseTime + tier.durationMs;
    const newPoints = currentPoints - tier.pointsCost;

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
  const utr = (request.data.utr || "").trim();
  const passTierId = request.data.passTier || request.data.passTierId || "daily_pass";
  const tier = PASS_TIERS[passTierId] || PASS_TIERS.daily_pass;

  if (!/^\d{12}$/.test(utr)) {
    throw new HttpsError("invalid-argument", "Invalid 12-digit numeric UTR.");
  }

  const paymentDocRef = db.collection("received_payments").document(utr);
  const userDocRef = db.collection("users_licensing").document(uid);

  return db.runTransaction(async (transaction) => {
    const paymentSnap = await transaction.get(paymentDocRef);

    if (!paymentSnap.exists) {
      // Record pending verification for admin review
      const pendingRef = db.collection("pending_verifications").document(utr);
      transaction.set(
        pendingRef,
        {
          utr,
          userId: uid,
          passTierId: tier.id,
          requestedAt: admin.firestore.FieldValue.serverTimestamp(),
          status: "PENDING_MATCH",
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

    const serverNow = Date.now();
    const userSnap = await transaction.get(userDocRef);
    const userData = userSnap.exists ? userSnap.data() : {};
    const currentExpiry = userData.passExpiryTimestamp || 0;
    const baseTime = currentExpiry > serverNow ? currentExpiry : serverNow;
    const newExpiry = baseTime + tier.durationMs;

    // Mark payment as claimed and used
    transaction.update(paymentDocRef, {
      isUsed: true,
      isClaimed: true,
      claimedBy: uid,
      claimedByUid: uid,
      claimedAt: admin.firestore.FieldValue.serverTimestamp(),
      passTierId: tier.id,
    });

    // Update user's licensing
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
 * Creates or ensures user licensing record in users_licensing with unique referral code.
 */
exports.initializeUserLicensing = onCall({ enforceAppCheck: false }, async (request) => {
  assertCallerSecurity(request);

  const uid = request.auth.uid;
  const userEmail = request.auth.token.email || "";
  const userDocRef = db.collection("users_licensing").document(uid);

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
  const submittedCode = (request.data.submittedCode || request.data.inviteCode || "").trim().toUpperCase();

  if (!submittedCode || submittedCode.length !== 8) {
    throw new HttpsError("invalid-argument", "Referral code must be exactly 8 characters.");
  }

  // Pre-query referrer
  const querySnap = await db.collection("users_licensing")
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

  const userDocRef = db.collection("users_licensing").document(uid);
  const referrerDocRef = db.collection("users_licensing").document(referrerUid);

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
  const featureId = (request.data.featureId || "").trim();
  const hours = parseInt(request.data.hours, 10) || 1;

  if (isNaN(hours) || hours <= 0 || hours > 720) {
    throw new HttpsError("invalid-argument", "Hours must be a positive integer (max 720).");
  }

  const pricing = FEATURE_PRICING[featureId];
  if (!pricing) {
    throw new HttpsError("invalid-argument", `Unknown feature ID: ${featureId}`);
  }

  const totalCost = pricing.pointsPerHour * hours;
  const userDocRef = db.collection("users_licensing").document(uid);

  return db.runTransaction(async (transaction) => {
    const userSnap = await transaction.get(userDocRef);
    if (!userSnap.exists) {
      throw new HttpsError("not-found", "User licensing document not found.");
    }

    const userData = userSnap.data();
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

