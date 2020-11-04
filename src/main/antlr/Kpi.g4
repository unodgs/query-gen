grammar Kpi;
@members {
public void storeAgg(Token agg) {};
}

init: expr EOF ;

expr : left=expr op=(MUL | DIV) right=expr   # MulDiv
     | left=expr op=(ADD | SUB) right=expr   # AddSub
     | LPAREN expr RPAREN                    # ParenExpr
     | AGG                                   { storeAgg($AGG); } # Agg
     | NUMBER                                # Number
     ;
     
AGG : LPAREN IDENTIFIER RPAREN ;

LPAREN : '(' ;
RPAREN : ')' ;
ADD : '+' ;
SUB : '-' ;
MUL : '*' ;
DIV : '/' ;

NUMBER : [0-9] + ('.' [0-9]+)? ;
IDENTIFIER : [A-Za-z0-9]+ ;

WS : [ \t\r\n]+ -> skip ;
