const { admin, db, authenticateRequest, PASS_TIERS } = require("./_utils/firebase");

module.exports = async (req, res) => {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");

  if (req.method === "OPTIONS") {
    return res.status(200).end();
  }

  const caller = await authenticateRequest(req);
  if (!caller) {
    return res.status(401).json({ error: "Authentication required." });
  }

  const uid = caller.uid;
  let body = req.body;
  if (typeof body === "string") {
    try { body = JSON.parse(body); } catch (_) { body = {}; }
  }
  body = body || {};
  const utr = ((body.utr) || "").trim();
  const passTierId = body.passTier || body.passTierId || "daily_pass";
  const tier = PASS_TIERS[passTierId] || PASS_TIERS.daily_pass;

  if (!/^\d{9,16}$/.test(utr)) {
    return res.status(400).json({ error: "Invalid numeric UTR reference (must be 9 to 16 digits)." });
  }

  const paymentDocRef = db.collection("received_payments").doc(utr);
  const userDocRef = db.collection("users_licensing").doc(uid);

  try {
    const result = await db.runTransaction(async (transaction) => {
      const paymentSnap = await transaction.get(paymentDocRef);

      if (!paymentSnap.exists) {
        // Record pending verification for admin review
        const pendingRef = db.collection("pending_verifications").doc(utr);
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

        throw new Error("Payment UTR not confirmed by bank yet. Pending admin verification.");
      }

      const paymentData = paymentSnap.data();
      if (paymentData.isClaimed || paymentData.claimedByUid || paymentData.isUsed) {
        throw new Error("This UTR has already been claimed or used.");
      }

      if (paymentData.status && paymentData.status !== "Verified" && paymentData.status !== "VERIFIED") {
        throw new Error("Payment has not been verified yet.");
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

    return res.status(200).json(result);
  } catch (err) {
    console.error("verifyUtrAndActivatePass error:", err);
    return res.status(400).json({ error: err.message || "Failed verifying payment UTR." });
  }
};
