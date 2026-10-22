// Address data remains authoritative; illustrations never infer a house from geolocation.
export function housePresentation(house){
  const address=house?.address?.trim()||'',parts=address.split(',').map(p=>p.trim());
  const first=parts[0].replace(/^г\.?\s+/iu,'');
  const streetFirst=/^(?:ул(?:ица)?|проспект|пр-т|пер(?:еулок)?|шоссе|бульвар|наб(?:ережная)?)(?=[.\s]|$)/iu.test(first);
  const hasCity=parts.length>1&&!streetFirst&&!/^\d/.test(first);
  return {city:hasCity?first:'',street:hasCity?parts.slice(1).join(', ').replace(/^(?:ул\.?|улица)\s+/iu,''):address,art:/^(?:г\.?\s+)?казань(?:\s*,|$)/iu.test(address)?'kazan-autumn':'universal'};
}
export function resolveAppearance(choice,maxScheme,systemDark){return ['light','dark'].includes(choice)?choice:['light','dark'].includes(maxScheme)?maxScheme:systemDark?'dark':'light';}
