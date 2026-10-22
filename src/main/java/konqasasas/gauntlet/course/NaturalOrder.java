package konqasasas.gauntlet.course;

/** Sorting names the way builders number them. */
public final class NaturalOrder {
    /** Text order where runs of digits compare as numbers ("m1-2" before "m1-10"). */
    public static int compare(String a,String b) {
        int i=0,j=0;
        while(i<a.length() && j<b.length()) {
            char x=a.charAt(i),y=b.charAt(j);
            if(Character.isDigit(x) && Character.isDigit(y)) {
                int si=i,sj=j;
                while(i<a.length() && Character.isDigit(a.charAt(i))) i++;
                while(j<b.length() && Character.isDigit(b.charAt(j))) j++;
                String na=a.substring(si,i).replaceFirst("^0+(?=.)",""),nb=b.substring(sj,j).replaceFirst("^0+(?=.)","");
                int c=na.length()!=nb.length() ? Integer.compare(na.length(),nb.length()) : na.compareTo(nb);
                if(c!=0) return c;
            } else {
                int c=Character.compare(Character.toLowerCase(x),Character.toLowerCase(y));
                if(c!=0) return c;
                i++; j++;
            }
        }
        return Integer.compare(a.length()-i,b.length()-j);
    }
    private NaturalOrder() {}
}
