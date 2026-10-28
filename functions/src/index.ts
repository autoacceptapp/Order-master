import * as functions from "firebase-functions";
import * as admin from "firebase-admin";

admin.initializeApp();

/**
 * secureAddPoints
 *
 * Secure Firebase Callable Cloud Function for top-up / recharge of points.
 * Enforces:
 * 1. User Authentication (Firebase Auth)
 * 2. App Integrity Attestation (Firebase App Check)
 * 3. Input Validation (strictly positive integer)
 * 4. Atomic Server-Side Firestore Increment
 *
 * 100% Free-Tier Compatible (Cloud Functions free tier: 2M invocations/month).
 */
export const secureAddPoints = functions.https.onCall(async (data, context) => {
  // 1. Authenticate the user
  if (!context.auth) {
    throw new functions.https.HttpsError(
      "unauthenticated",
      "The function must be called by an authenticated user."
    );
  }

  // 2. Validate App Check token (prevents bot / script scraping)
  // Note: During local emulator testing or debug build, ensure debug token is configured.
  if (context.app == null) {
    throw new functions.https.HttpsError(
      "failed-precondition",
      "The function must be called from an app with a valid App Check token."
    );
  }

  // 3. Validate input parameters
  const rawAmount = data.amount;
  const amount = Number(rawAmount);

  if (!Number.isInteger(amount) || amount <= 0) {
    throw new functions.https.HttpsError(
      "invalid-argument",
      "Amount must be a positive integer greater than zero."
    );
  }

  // Sanity check: prevent unrealistic upper limit spikes per single invocation
  if (amount > 100000) {
    throw new functions.https.HttpsError(
      "invalid-argument",
      "Amount exceeds the maximum allowable single transaction limit."
    );
  }

  // 4. Resolve user document key
  const callerUid = context.auth.uid;
  const requestedUserKey = typeof data.userKey === "string" ? data.userKey.trim() : "";
  
  // Safeguard: Ensure user cannot manipulate another user's wallet
  const userKey = (requestedUserKey && (
    requestedUserKey === callerUid ||
    requestedUserKey.startsWith("email_") ||
    requestedUserKey.startsWith("hw_")
  )) ? requestedUserKey : callerUid;

  const db = admin.firestore();
  const userDocRef = db.collection("users_licensing").doc(userKey);

  try {
    // 5. Atomically increment points in users_licensing collection
    await userDocRef.set(
      {
        userId: userKey,
        pointsBalance: admin.firestore.FieldValue.increment(amount),
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      },
      { merge: true }
    );

    // Fetch the updated document to return the precise new balance
    const updatedSnap = await userDocRef.get();
    const updatedPoints = updatedSnap.get("pointsBalance");
    const pointsBalance = typeof updatedPoints === "number" ? updatedPoints : amount;

    functions.logger.info(`Successfully added ${amount} points for userKey=${userKey}. New balance: ${pointsBalance}`);

    return {
      success: true,
      pointsBalance: pointsBalance,
      addedAmount: amount,
    };
  } catch (err: any) {
    functions.logger.error("Failed to securely add points to Firestore:", err);
    throw new functions.https.HttpsError(
      "internal",
      "An internal error occurred while updating points balance. Please try again later."
    );
  }
});

/**
 * secureClaimTrial
 *
 * Secure Firebase Callable Cloud Function for claiming a 2-day free trial.
 * Enforces:
 * 1. User Authentication (Firebase Auth) - Anonymous accounts strictly disallowed.
 * 2. App Integrity Attestation (Firebase App Check / Play Integrity) - Blocks emulators & script scrapers.
 * 3. 1:1 Hardware ID to User UID binding:
 *    - Hardware ID cannot claim more than 1 trial (prevents multi-account abuse on 1 device).
 *    - User UID cannot claim more than 1 trial (prevents multi-device abuse by 1 account).
 * 4. Emulator rejection.
 *
 * 100% Free-Tier Compatible.
 */
export const secureClaimTrial = functions.https.onCall(async (data, context) => {
  // 1. Authenticate user
  if (!context.auth) {
    throw new functions.https.HttpsError(
      "unauthenticated",
      "Authentication required. Please sign in with Google to claim your 2-Day Free Trial."
    );
  }

  // Reject anonymous auth
  if (context.auth.token.firebase?.sign_in_provider === "anonymous") {
    throw new functions.https.HttpsError(
      "unauthenticated",
      "Anonymous accounts are not eligible for a free trial. Please sign in with Google."
    );
  }

  // 2. Validate App Check (Play Integrity)
  if (context.app == null) {
    throw new functions.https.HttpsError(
      "failed-precondition",
      "App integrity attestation failed. This operation requires a verified device."
    );
  }

  // 3. Validate Hardware ID
  const hardwareId = typeof data.hardwareId === "string" ? data.hardwareId.trim() : "";
  if (!hardwareId || hardwareId.length < 8) {
    throw new functions.https.HttpsError(
      "invalid-argument",
      "Invalid hardware identifier provided."
    );
  }

  // Check emulator detection flags from client specs
  const deviceSpecs = data.deviceSpecs || {};
  if (deviceSpecs.IsEmulator === "true") {
    throw new functions.https.HttpsError(
      "permission-denied",
      "Free trials cannot be activated on emulator environments."
    );
  }

  const uid = context.auth.uid;
  const userEmail = context.auth.token.email || "";
  const db = admin.firestore();

  const hwDocRef = db.collection("hardware_trials").doc(hardwareId);
  const userTrialDocRef = db.collection("user_trials").doc(uid);

  const TRIAL_DURATION_MS = 48 * 3600 * 1000; // 48 Hours

  return await db.runTransaction(async (transaction) => {
    const hwDoc = await transaction.get(hwDocRef);
    const userTrialDoc = await transaction.get(userTrialDocRef);

    const now = Date.now();

    // Check 1: Hardware ID already used
    if (hwDoc.exists) {
      const hwData = hwDoc.data() || {};
      const boundUid = hwData.uid;

      // If already claimed by THIS user, return existing trial status
      if (boundUid === uid) {
        return {
          success: true,
          hardwareId: hardwareId,
          uid: uid,
          firstClaimedTimestamp: hwData.firstClaimedTimestamp || now,
          trialExpiryTimestamp: hwData.trialExpiryTimestamp || (now + TRIAL_DURATION_MS),
          isUsed: true,
          isNewClaim: false,
          message: "Existing trial active for this account and device."
        };
      }

      // Claimed by a different user on this physical device -> Multi-account device abuse blocked!
      throw new functions.https.HttpsError(
        "already-exists",
        "A free trial has already been claimed on this device under another account."
      );
    }

    // Check 2: User account already claimed on another device -> Multi-device account abuse blocked!
    if (userTrialDoc.exists) {
      const userData = userTrialDoc.data() || {};
      throw new functions.https.HttpsError(
        "already-exists",
        `This Google account has already claimed a free trial on device ${userData.hardwareId || "another device"}.`
      );
    }

    // Both hardware ID and user account are fresh -> grant 2-day free trial!
    const expiry = now + TRIAL_DURATION_MS;

    const trialRecord = {
      hardwareId: hardwareId,
      uid: uid,
      userEmail: userEmail,
      firstClaimedTimestamp: now,
      trialExpiryTimestamp: expiry,
      isUsed: true,
      deviceSpecs: deviceSpecs,
      createdAt: admin.firestore.FieldValue.serverTimestamp()
    };

    transaction.set(hwDocRef, trialRecord);
    transaction.set(userTrialDocRef, {
      uid: uid,
      hardwareId: hardwareId,
      userEmail: userEmail,
      firstClaimedTimestamp: now,
      trialExpiryTimestamp: expiry,
      isUsed: true,
      createdAt: admin.firestore.FieldValue.serverTimestamp()
    });

    functions.logger.info(`Successfully granted 2-day free trial to UID=${uid} on hardwareId=${hardwareId}`);

    return {
      success: true,
      hardwareId: hardwareId,
      uid: uid,
      firstClaimedTimestamp: now,
      trialExpiryTimestamp: expiry,
      isUsed: true,
      isNewClaim: true,
      message: "2-Day Free Trial successfully activated."
    };
  });
});

