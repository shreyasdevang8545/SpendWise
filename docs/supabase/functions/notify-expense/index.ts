import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.0"
import * as jose from "https://esm.sh/jose@5.2.0"

/**
 * Helper to get a fresh FCM Access Token using manual JWT signing
 */
async function getFcmAccessToken(serviceAccount: any) {
  const { client_email, private_key } = serviceAccount

  // 1. Prepare the JWT
  const jwt = await new jose.SignJWT({
    scope: 'https://www.googleapis.com/auth/firebase.messaging',
  })
    .setProtectedHeader({ alg: 'RS256' })
    .setIssuer(client_email)
    .setAudience('https://oauth2.googleapis.com/token')
    .setExpirationTime('1h')
    .setIssuedAt()
    .sign(await jose.importPKCS8(private_key, 'RS256'))

  // 2. Exchange JWT for Access Token
  const response = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
      assertion: jwt,
    }),
  })

  const data = await response.json()
  return data.access_token
}

serve(async (req) => {
  try {
    const { record } = await req.json()
    const { amount, description, group_id, paid_by, uid: senderUid } = record

    // Initialize Supabase Client
    const supabaseAdmin = createClient(
      Deno.env.get('SUPABASE_URL') ?? '',
      Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? ''
    )

    // 1. Fetch group details to get the group name
    const { data: group } = await supabaseAdmin
      .from('split_groups')
      .select('name, participants')
      .eq('id', group_id)
      .single()

    if (!group) return new Response(JSON.stringify({ error: 'Group not found' }), { status: 404 })

    // 2. Fetch sender's name
    const senderName = paid_by.split('|')[0]

    // 3. Fetch all participant FCM tokens except the sender
    const participants = group.participants.filter((uid: string) => uid !== senderUid)
    
    if (participants.length === 0) {
      return new Response(JSON.stringify({ message: 'No other participants to notify' }), { status: 200 })
    }

    const { data: tokens } = await supabaseAdmin
      .from('user_fcm_tokens')
      .select('fcm_token')
      .in('uid', participants)

    if (!tokens || tokens.length === 0) {
      return new Response(JSON.stringify({ message: 'No FCM tokens found' }), { status: 200 })
    }

    const fcmTokens = tokens.map((t: any) => t.fcm_token)

    // 4. Send FCM Notification (FCM v1 API)
    const serviceAccount = JSON.parse(Deno.env.get('FIREBASE_SERVICE_ACCOUNT') ?? '{}')
    const accessToken = await getFcmAccessToken(serviceAccount)
    const projectId = serviceAccount.project_id

    for (const token of fcmTokens) {
      const fcmResponse = await fetch(`https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${accessToken}`
        },
        body: JSON.stringify({
          message: {
            token: token,
            notification: {
              title: `${group.name}`,
              body: `${senderName} added an expense: ₹${amount} - ${description}`
            },
            data: {
              group_id: group_id,
              expense_id: record.id
            }
          }
        })
      })

      const result = await fcmResponse.json()
      console.log(`FCM Result for ${token.substring(0, 10)}...:`, result)
    }

    return new Response(JSON.stringify({ success: true, notified_count: fcmTokens.length }), {
      headers: { "Content-Type": "application/json" },
      status: 200,
    })

  } catch (error) {
    console.error('Error in notify-expense:', error)
    return new Response(JSON.stringify({ error: error.message }), {
      headers: { "Content-Type": "application/json" },
      status: 500,
    })
  }
})

/**
 * INSTRUCTIONS:
 * 1. Go to Firebase Console -> Project Settings -> Service Accounts.
 * 2. Click "Generate new private key" to download the JSON file.
 * 3. Copy the ENTIRE content of that JSON file.
 * 4. Set it as a Supabase Secret named 'FIREBASE_SERVICE_ACCOUNT':
 *    supabase secrets set FIREBASE_SERVICE_ACCOUNT='{...copy json here...}'
 */
