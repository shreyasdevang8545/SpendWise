import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.0"
import * as jose from "https://esm.sh/jose@5.2.0"

/**
 * Helper to get a fresh FCM Access Token using manual JWT signing
 */
async function getFcmAccessToken(serviceAccount: any) {
  const { client_email, private_key } = serviceAccount
  const jwt = await new jose.SignJWT({
    scope: 'https://www.googleapis.com/auth/firebase.messaging',
  })
    .setProtectedHeader({ alg: 'RS256' })
    .setIssuer(client_email)
    .setAudience('https://oauth2.googleapis.com/token')
    .setExpirationTime('1h')
    .setIssuedAt()
    .sign(await jose.importPKCS8(private_key, 'RS256'))

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
  console.log('--- Edge Function notify-join received a request ---')
  try {
    const body = await req.json()
    console.log('Request Body:', JSON.stringify(body, null, 2))
    
    const { record, old_record, type } = body
    
    // We only care about UPDATE where participants list grows
    if (type !== 'UPDATE') return new Response(JSON.stringify({ message: 'Ignored non-update event' }))

    const oldParticipants = old_record.participants || []
    const newParticipants = record.participants || []

    // Find the new member
    const newUid = newParticipants.find((uid: string) => !oldParticipants.includes(uid))
    if (!newUid) return new Response(JSON.stringify({ message: 'No new participant found' }))

    const ownerUid = record.uid
    // Don't notify if the participant is the owner
    if (newUid === ownerUid) return new Response(JSON.stringify({ message: 'Owner joined their own group' }))

    // Initialize Supabase Client
    const supabaseAdmin = createClient(
      Deno.env.get('SUPABASE_URL') ?? '',
      Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? ''
    )

    // 1. Get New Member's Name from mappings
    const mapping = record.member_mappings || {}
    const newMemberName = mapping[newUid] || 'Someone'

    // 2. Fetch Owner's FCM Token
    const { data: tokenData } = await supabaseAdmin
      .from('user_fcm_tokens')
      .select('fcm_token')
      .eq('uid', ownerUid)
      .single()

    if (!tokenData?.fcm_token) return new Response(JSON.stringify({ message: 'Owner has no FCM token' }))

    // 3. Send Notification to Owner
    const serviceAccount = JSON.parse(Deno.env.get('FIREBASE_SERVICE_ACCOUNT') ?? '{}')
    const accessToken = await getFcmAccessToken(serviceAccount)
    
    const fcmResponse = await fetch(`https://fcm.googleapis.com/v1/projects/${serviceAccount.project_id}/messages:send`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${accessToken}`
      },
      body: JSON.stringify({
        message: {
          token: tokenData.fcm_token,
          notification: {
            title: `Member Joined Group`,
            body: `${newMemberName} has joined your group "${record.name}"`
          },
          data: {
            group_id: record.id,
            action: 'member_joined'
          }
        }
      })
    })

    const result = await fcmResponse.json()
    console.log(`Join notification sent to owner:`, result)

    return new Response(JSON.stringify({ success: true }), { status: 200 })

  } catch (error) {
    console.error('Error in notify-join:', error)
    return new Response(JSON.stringify({ error: error.message }), { status: 500 })
  }
})
