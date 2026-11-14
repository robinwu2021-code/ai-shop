#!/usr/bin/env python3
"""Opt-in deployed store-plan smoke check; never submits or confirms receipt of money.

Uses the already authorized automation identity. Credentials stay in memory.
Creates named test plans, disables them in finally, and tests an unknown-create
cancellation fence with a synthetic, unresolvable customer reference. No wallet
or recharge financial row should be created.
"""
import argparse
import json
import os
import secrets
import urllib.error
import urllib.parse
import urllib.request
from _ticket import BASE_URL, session


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--subject', required=True)
    parser.add_argument('--store', required=True)
    parser.add_argument('--other-store', required=True)
    parser.add_argument('--existing-plan')
    parser.add_argument('--write-test-plans', action='store_true')
    args = parser.parse_args()
    if not args.write_test_plans:
        parser.error('Explicit --write-test-plans is required for this non-posting smoke workflow')
    os.environ['AUTOMATION_STORE_NO'] = args.store
    token = session('B', args.subject)
    run = secrets.token_hex(4)
    results, plans = [], []

    def call(method, path, body=None, key=None, store=None, success=True):
        headers = {'Content-Type': 'application/json', 'Authorization': 'Bearer ' + token,
                   'X-Store-No': store or args.store}
        if key:
            headers['Idempotency-Key'] = key
        req = urllib.request.Request(BASE_URL + '/biz/stored-value' + path,
                data=None if body is None else json.dumps(body).encode(), method=method, headers=headers)
        try:
            response = urllib.request.urlopen(req, timeout=30)
        except urllib.error.HTTPError as e:
            response = e
        value = json.loads(response.read())
        code = value.get('code')
        assert (code == 0) == success, f'{method} {path.split("?")[0]} unexpected code={code}'
        results.append({'method': method, 'path': path.split('?')[0], 'code': code})
        return value.get('data') if success else code

    query = lambda store: '?'+urllib.parse.urlencode({'storeNo':store})
    try:
        context = call('GET', '/context' + query(args.store))
        assert context['currency'] == 'CNY'
        if args.existing_plan:
            plans.append(call('GET','/plans/'+args.existing_plan+query(args.store)))
        for paid, bonus in [(10000,3000),(20000,5000)]:
            body = {'storeNo':args.store,'name':f'联调充{paid//100}送{bonus//100}-{run}',
                    'currency':'CNY','paidAmountMinor':paid,'bonusAmountMinor':bonus}
            key = 'sv-plan-'+secrets.token_hex(12)
            p = call('POST','/plans',body,key)
            plans.append(p)
            replay = call('POST','/plans',body,key)
            assert replay['planNo'] == p['planNo'] and p['totalAmountMinor'] == paid+bonus
            call('POST','/plans',{**body,'paidAmountMinor':paid+1},key,success=False)
            call('GET','/plans/'+p['planNo']+query(args.other_store),store=args.other_store,success=False)
            # Revision conflict must not mutate the plan.
            call('PUT','/plans/'+p['planNo']+'/status',{'storeNo':args.store,'status':'INACTIVE',
                    'expectedRevision':p['revision']+100},secrets.token_hex(16),success=False)
        directory = call('GET','/plans'+query(args.store)+'&size=1')
        assert directory['nextCursor']
        cursor = urllib.parse.quote(directory['nextCursor'],safe='')
        second = call('GET','/plans'+query(args.store)+'&size=1&cursor='+cursor)
        assert directory['records'][0]['planNo'] != second['records'][0]['planNo']
        for kind in ['ALL','RECHARGE','REDEMPTION']:
            data=call('GET','/records'+query(args.store)+'&type='+kind+'&startDate=2026-10-01&endDate=2026-10-20')
            assert data['records']==[]
        assert call('GET','/account-directory'+query(args.store))['records']==[]
        assert call('GET','/account-directory'+query(args.store)+'&keyword=5678')['records']==[]
        other = call('GET','/plans'+query(args.other_store),store=args.other_store)
        assert not {p['planNo'] for p in plans}.intersection(p['planNo'] for p in other['records'])
        p=plans[-1]
        original={'storeNo':args.store,'customerRef':'cr1_'+secrets.token_urlsafe(32),
                  'planNo':p['planNo'],'versionNo':p['versionNo']}
        key='sv-unknown-'+secrets.token_hex(12)
        assert call('POST','/plan-recharges/resolve',original,key)['found'] is False
        assert call('POST','/plan-recharges/cancel-create',original,key)['status']=='CANCELLED'
        # This late create replays the tombstone, without resolving a customer or adding money.
        assert call('POST','/plan-recharges',original,key)['status']=='CANCELLED'
        assert call('POST','/plan-recharges/resolve',original,key)['found'] is True
        print(json.dumps({'run':run,'checks':results,'planNos':[p['planNo'] for p in plans],
                          'financialPosting':False},ensure_ascii=False))
    finally:
        for plan in plans:
            current=call('GET','/plans/'+plan['planNo']+query(args.store))
            if current['status']=='ACTIVE':
                body={'storeNo':args.store,'status':'INACTIVE','expectedRevision':current['revision']}
                key='sv-disable-'+secrets.token_hex(12)
                disabled=call('PUT','/plans/'+plan['planNo']+'/status',body,key)
                assert disabled['status']=='INACTIVE'
                assert call('PUT','/plans/'+plan['planNo']+'/status',body,key)['revision']==disabled['revision']
        print(json.dumps({'cleanup':'test plans disabled','count':len(plans)}))


if __name__ == '__main__':
    main()
